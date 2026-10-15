package com.offermate.modules.resume.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.offermate.common.exception.BizException;
import com.offermate.modules.resume.entity.ResumeFile;
import com.offermate.modules.resume.mapper.ResumeFileMapper;
import com.offermate.modules.resume.service.ResumeUploadService;
import com.offermate.mq.MqEvents;
import com.offermate.mq.MqProducer;
import com.offermate.mq.MqTopics;
import io.minio.ComposeObjectArgs;
import io.minio.ComposeSource;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * 分片上传 + 断点续传 + MD5 内容级秒传。
 *
 * ── 分片方案说明（面试会问，务必讲清楚）────────────────────────────
 * minio-java 公开 API 里没有 S3 MultipartUpload 的 Args 类，
 * 所以这里采用业界同样常见的实现方式：
 *   1) 前端 5MB 切片，逐片调用本服务；
 *   2) 每片先作为一个独立对象落到 chunks/{uploadId}/{partNumber}；
 *   3) 全部分片到齐后，用 MinIO ComposeObject 在**服务端**拼接成最终对象。
 * 取舍：ComposeObject 是服务端 copy 拼接，不是 S3 MultipartUpload 的零拷贝；
 * 分片对象在合并成功后立即删除，因此会有「峰值 2 倍存储 + 一次额外 IO」的成本。
 * 若换成 AWS SDK 的 S3Client（同样对接 MinIO），可直接用 CreateMultipartUpload 做到零拷贝拼接，
 * 这是本项目明确记录的优化项。
 *
 * ── Redis 结构 ────────────────────────────────────────────────
 *   upload:parts:{uploadId}  Hash   partNumber -> size   （合并时校验完整性）
 *   upload:bitmap:{uploadId} Bitmap 第 partNumber 位 = 1 表示已上传（续传探测，O(1) 且省内存）
 *   upload:owner:{uploadId}  String uploadId 归属的 userId（防止越权传别人的分片）
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ResumeUploadServiceImpl implements ResumeUploadService {

    /** 除最后一片外，每片必须 >= 5MB（S3 对 UploadPartCopy 的硬性限制） */
    private static final long MIN_PART_SIZE = 5 * 1024 * 1024L;
    private static final long MAX_PART_SIZE = 200 * 1024 * 1024L;
    private static final int MAX_PARTS = 10000;

    private final MinioClient minioClient;
    private final ResumeFileMapper resumeFileMapper;
    private final RedissonClient redissonClient;
    private final StringRedisTemplate redis;
    private final MqProducer mqProducer;

    @Value("${minio.bucket}")
    private String bucket;

    @Value("${offermate.upload.part-state-ttl-hours:24}")
    private long partStateTtlHours;

    @Override
    public InitResult init(Long userId, String fileName, String fileMd5, long size) {
        // 1) 内容级去重 → 秒传：同一份文件内容直接复用已有对象，前端一个字节都不用传
        ResumeFile uploaded = resumeFileMapper.selectOne(Wrappers.<ResumeFile>lambdaQuery()
                .eq(ResumeFile::getMd5, fileMd5)
                .eq(ResumeFile::getStatus, ResumeFile.STATUS_DONE)
                .last("limit 1"));
        if (uploaded != null) {
            // ① 抗碰撞补强：指纹相同但大小对不上 → 直接拒绝（不同内容极少连大小都一样）
            if (size > 0 && uploaded.getSize() != null && uploaded.getSize() > 0
                    && uploaded.getSize().longValue() != size) {
                log.warn("指纹相同但大小不同，疑似碰撞: md5={}, 库中={}, 本次={}", fileMd5, uploaded.getSize(), size);
                throw new BizException("文件校验未通过，请重新上传");
            }
            // ② 归属校验：这条记录是不是当前用户自己的？
            if (Objects.equals(uploaded.getUserId(), userId)) {
                log.info("秒传命中(本人记录), md5={}, fileId={}", fileMd5, uploaded.getId());
                return new InitResult(true, null, uploaded.getObjectKey(), uploaded.getId(), List.of());
            }
            // ③ 不是自己的：只复用「存储对象」，绝不把别人的记录 ID 返回出去
            ResumeFile mine = new ResumeFile();
            mine.setUserId(userId);
            mine.setMd5(fileMd5);
            mine.setFileName(fileName);
            mine.setObjectKey(uploaded.getObjectKey());
            mine.setSize(uploaded.getSize());
            mine.setStatus(ResumeFile.STATUS_DONE);
            mine.setCreateTime(LocalDateTime.now());
            mine.setUpdateTime(LocalDateTime.now());
            try {
                resumeFileMapper.insert(mine);
                log.info("跨用户秒传: 复用存储对象并新建归属记录 userId={}, md5={}", userId, fileMd5);
                return new InitResult(true, null, mine.getObjectKey(), mine.getId(), List.of());
            } catch (DuplicateKeyException e) {
                // (md5, user_id) 唯一索引冲突：我自己本来就有这条记录（可能被逻辑删除或没传完）→ 修正它
                ResumeFile own = resumeFileMapper.selectIncludingDeleted(fileMd5, userId);
                if (own == null) {
                    throw new BizException("秒传失败，请重试");
                }
                resumeFileMapper.revive(own.getId(), ResumeFile.STATUS_DONE,
                        uploaded.getObjectKey(), uploaded.getSize());
                return new InitResult(true, null, uploaded.getObjectKey(), own.getId(), List.of());
            }
        }

        // 2) 断点续传：该文件还有未完成的上传任务，把 uploadId 与已传分片还给前端
        ResumeFile unfinished = resumeFileMapper.selectOne(Wrappers.<ResumeFile>lambdaQuery()
                .eq(ResumeFile::getMd5, fileMd5)
                .eq(ResumeFile::getStatus, ResumeFile.STATUS_UPLOADING)
                .isNotNull(ResumeFile::getUploadId)
                .last("limit 1"));
        if (unfinished != null) {
            String owner = redis.opsForValue().get(ownerKey(unfinished.getUploadId()));
            if (String.valueOf(userId).equals(owner)) {
                List<Integer> parts = uploadedParts(unfinished.getUploadId());
                log.info("断点续传命中, uploadId={}, 已上传分片数={}", unfinished.getUploadId(), parts.size());
                return new InitResult(false, unfinished.getUploadId(), unfinished.getObjectKey(),
                        unfinished.getId(), parts);
            }
        }

        // 3) 全新上传：自建 uploadId（业务侧生成，Redis 与 MinIO 都用它组织分片）
        String uploadId = UUID.randomUUID().toString().replace("-", "");
        String objectKey = buildObjectKey(userId, fileMd5, fileName);

        ResumeFile file = new ResumeFile();
        file.setUserId(userId);
        file.setMd5(fileMd5);
        file.setFileName(fileName);
        file.setObjectKey(objectKey);
        file.setSize(size);
        file.setUploadId(uploadId);
        file.setStatus(ResumeFile.STATUS_UPLOADING);
        file.setCreateTime(LocalDateTime.now());
        file.setUpdateTime(LocalDateTime.now());
        try {
            resumeFileMapper.insert(file);
        } catch (DuplicateKeyException e) {
            // (md5, user_id) 唯一索引兜底：并发下「我自己」已经落过库 → 复用我自己的记录，绝不返回别人的
            ResumeFile concurrent = resumeFileMapper.selectIncludingDeleted(fileMd5, userId);
            if (concurrent == null) {
                throw new BizException("上传任务创建失败，请重试");
            }
            if (ResumeFile.STATUS_DONE.equals(concurrent.getStatus())) {
                return new InitResult(true, null, concurrent.getObjectKey(), concurrent.getId(), List.of());
            }
            if (!ResumeFile.STATUS_UPLOADING.equals(concurrent.getStatus()) || concurrent.getUploadId() == null) {
                // 上一次任务已经失败（例如分片大小非法）。这条记录还占着 (md5, user_id) 唯一键，
                // 不能再插一条；但可以复用这条记录、换一个全新的 uploadId 重新开始——
                // 否则前端"重新上传"会一直命中这个坏任务，卡死。
                String freshUploadId = UUID.randomUUID().toString().replace("-", "");
                resumeFileMapper.resetTask(concurrent.getId(), ResumeFile.STATUS_UPLOADING, freshUploadId);
                redis.opsForValue().set(ownerKey(freshUploadId), String.valueOf(userId),
                        Duration.ofHours(partStateTtlHours));
                log.info("旧任务已失败，已重置为新任务: fileId={}, 新 uploadId={}", concurrent.getId(), freshUploadId);
                return new InitResult(false, freshUploadId, concurrent.getObjectKey(), concurrent.getId(), List.of());
            }
            String existingUploadId = concurrent.getUploadId();
            return new InitResult(false, existingUploadId, concurrent.getObjectKey(), concurrent.getId(),
                    uploadedParts(existingUploadId));
        }
        redis.opsForValue().set(ownerKey(uploadId), String.valueOf(userId), Duration.ofHours(partStateTtlHours));
        return new InitResult(false, uploadId, objectKey, file.getId(), List.of());
    }

    @Override
    public ChunkResult uploadChunk(Long userId, String uploadId, int partNumber, long size, InputStream stream) {
        requireOwnedFile(userId, uploadId);
        if (partNumber < 1 || partNumber > MAX_PARTS) {
            throw new BizException("分片序号非法");
        }
        if (size <= 0 || size > MAX_PART_SIZE) {
            throw new BizException("分片大小非法");
        }
        String chunkObjectKey = chunkObjectKey(uploadId, partNumber);
        try {
            minioClient.putObject(PutObjectArgs.builder()
                    .bucket(bucket)
                    .object(chunkObjectKey)
                    .stream(stream, size, -1)
                    .contentType("application/octet-stream")
                    .build());

            // 分片元数据：Hash 存每片大小，合并前用它校验「分片是否到齐」
            redis.opsForHash().put(partsKey(uploadId), String.valueOf(partNumber), String.valueOf(size));
            // 分片位图：SETBIT 一位表示已传，续传时 GETBIT 即可，O(1) 且极省内存
            redis.opsForValue().setBit(bitmapKey(uploadId), partNumber, true);
            expirePartState(uploadId);
            return new ChunkResult(partNumber, chunkObjectKey);
        } catch (Exception e) {
            throw new BizException("分片 " + partNumber + " 上传失败: " + e.getMessage());
        }
    }

    @Override
    public MergeResult merge(Long userId, String uploadId, int totalParts) {
        ResumeFile file = requireOwnedFile(userId, uploadId);
        if (ResumeFile.STATUS_DONE.equals(file.getStatus())) {
            // 合并幂等：重复点击合并直接返回结果，不重复拼接
            return new MergeResult(file.getId(), file.getMd5(), file.getObjectKey(), "PARSING");
        }
        if (totalParts < 1 || totalParts > MAX_PARTS) {
            throw new BizException("分片总数非法");
        }

        RLock lock = redissonClient.getLock("lock:resume:merge:" + file.getMd5());
        boolean locked = false;
        try {
            // leaseTime = -1 → 启用 WatchDog，默认每 10s 把锁续期到 30s；
            // 合并与服务端拼接可能超过 30s，若不续期会出现「锁提前释放 → 并发重复合并」。
            locked = lock.tryLock(3, -1, TimeUnit.SECONDS);
            if (!locked) {
                throw new BizException("文件正在合并中，请稍后查询结果");
            }
            List<ComposeSource> sources = collectComposeSources(uploadId, totalParts);

            minioClient.composeObject(ComposeObjectArgs.builder()
                    .bucket(bucket)
                    .object(file.getObjectKey())
                    .sources(sources)
                    .build());

            file.setStatus(ResumeFile.STATUS_DONE);
            file.setUpdateTime(LocalDateTime.now());
            resumeFileMapper.updateById(file);

            clearPartState(uploadId, totalParts);
            log.info("分片合并完成, fileId={}, md5={}, parts={}", file.getId(), file.getMd5(), sources.size());
        } catch (com.offermate.modules.resume.exception.PartSizeInvalidException e) {
            // 切片规则错了 → 这个任务已经没救了：
            // ① 标记为失败（这样前端下次 init 不会再命中它，而是新建任务）
            // ② 清掉分片对象和 Redis 状态，不留垃圾
            file.setStatus(ResumeFile.STATUS_FAILED);
            file.setUpdateTime(LocalDateTime.now());
            resumeFileMapper.updateById(file);
            clearPartState(uploadId, totalParts);
            log.warn("分片大小非法，任务已作废: uploadId={}, 原因={}", uploadId, e.getMessage());
            throw e;
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            // 普通失败（比如存储抖了一下）：**分片对象和 Redis 状态都保留**，并把状态退回「上传中」。
            // 这样用户可以直接重试合并、或者只补传缺的那几片，完全不用整个重传。
            // （只有「分片大小非法」这种不可恢复的错误才会把任务标成 FAILED 并清掉分片——见上面的 catch）
            file.setStatus(ResumeFile.STATUS_UPLOADING);
            file.setUpdateTime(LocalDateTime.now());
            resumeFileMapper.updateById(file);
            log.warn("分片合并失败(可重试): uploadId={}, 原因={}", uploadId, e.getMessage());
            throw new BizException("分片合并失败：" + e.getMessage() + "，可直接重试合并");
        } finally {
            if (locked && lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }

        // 合并只代表「文件到位」，解析 / AI 结构化 / 匹配打分全部异步，主链路立即返回
        mqProducer.sendAsync(MqTopics.RESUME_PARSE,
                "resume:" + file.getId(),
                new MqEvents.ResumeParseEvent(file.getId(), file.getUserId(), file.getObjectKey(),
                        file.getMd5(), file.getFileName(), 0));
        return new MergeResult(file.getId(), file.getMd5(), file.getObjectKey(), "PARSING");
    }

    @Override
    public List<Integer> uploadedParts(String uploadId) {
        Map<Object, Object> entries = redis.opsForHash().entries(partsKey(uploadId));
        List<Integer> parts = new ArrayList<>(entries.size());
        entries.keySet().forEach(k -> parts.add(Integer.parseInt(String.valueOf(k))));
        Collections.sort(parts);
        return parts;
    }

    /** 按分片序号顺序构造拼接源，并校验分片是否到齐（缺失时把缺失分片号回给前端）。 */
    private List<ComposeSource> collectComposeSources(String uploadId, int totalParts) {
        // ① 先用 Bitmap 数一下"到了几片"：BITCOUNT 一次调用就能数出所有为 1 的位
        //    （一位一片，5000 片也才 625 字节），比拉整个 Hash 回来更省。
        long arrived = countArrivedParts(uploadId);
        if (arrived < totalParts) {
            log.warn("分片未到齐: uploadId={}, 已到 {}/{}", uploadId, arrived, totalParts);
        }

        // ② 到齐判断之后，再从 Hash 取"每片多大"：既用于 5MB 校验，也用来算出缺失的具体序号
        Map<Object, Object> entries = redis.opsForHash().entries(partsKey(uploadId));
        List<ComposeSource> sources = new ArrayList<>(totalParts);
        List<Integer> missing = new ArrayList<>();
        for (int i = 1; i <= totalParts; i++) {
            Object size = entries.get(String.valueOf(i));
            if (size == null) {
                missing.add(i);
                continue;
            }
            // 非末片小于 5MB 会被 MinIO 拒绝（EntityTooSmall），提前拦截并给出更友好的提示。
            // 注意：这种情况没法"只补这一片"——切片大小是整个文件的属性，改了规则所有分片边界都会变，
            // 所以抛专门的异常，由 merge 把这个任务作废掉（否则前端重新上传会一直命中这个坏任务）。
            if (i < totalParts && Long.parseLong(String.valueOf(size)) < MIN_PART_SIZE) {
                throw new com.offermate.modules.resume.exception.PartSizeInvalidException(
                        "分片 " + i + " 小于 5MB（除最后一片外每片不小于 5MB），请调整切片大小后重新上传文件");
            }
            sources.add(ComposeSource.builder()
                    .bucket(bucket)
                    .object(chunkObjectKey(uploadId, i))
                    .build());
        }
        if (!missing.isEmpty()) {
            throw new BizException("分片缺失，请续传后重试: " + missing);
        }
        return sources;
    }

    /** 用 Bitmap 的 BITCOUNT 数出"已到分片数"（一位一片，一次调用即可）。 */
    private long countArrivedParts(String uploadId) {
        Long count = redis.execute((org.springframework.data.redis.core.RedisCallback<Long>) connection ->
                connection.stringCommands().bitCount(
                        bitmapKey(uploadId).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        return count == null ? 0L : count;
    }

    /** 合并成功后清理分片对象与 Redis 中间状态，释放存储与内存。 */
    private void clearPartState(String uploadId, int totalParts) {
        for (int i = 1; i <= totalParts; i++) {
            try {
                minioClient.removeObject(RemoveObjectArgs.builder()
                        .bucket(bucket).object(chunkObjectKey(uploadId, i)).build());
            } catch (Exception e) {
                log.debug("清理分片对象失败 uploadId={}, part={}", uploadId, i);
            }
        }
        redis.delete(List.of(partsKey(uploadId), bitmapKey(uploadId), ownerKey(uploadId)));
    }

    private ResumeFile requireOwnedFile(Long userId, String uploadId) {
        ResumeFile file = resumeFileMapper.selectOne(Wrappers.<ResumeFile>lambdaQuery()
                .eq(ResumeFile::getUploadId, uploadId).last("limit 1"));
        if (file == null) {
            throw new BizException("上传任务不存在或已过期");
        }
        if (!Objects.equals(file.getUserId(), userId)) {
            throw new BizException(403, "无权操作该上传任务");
        }
        return file;
    }

    private String buildObjectKey(Long userId, String md5, String fileName) {
        return "resume/" + userId + "/" + md5 + "/" + fileName;
    }

    private String chunkObjectKey(String uploadId, int partNumber) {
        return "chunks/" + uploadId + "/" + partNumber;
    }

    private String partsKey(String uploadId) {
        return "upload:parts:" + uploadId;
    }

    private String bitmapKey(String uploadId) {
        return "upload:bitmap:" + uploadId;
    }

    private String ownerKey(String uploadId) {
        return "upload:owner:" + uploadId;
    }

    private void expirePartState(String uploadId) {
        redis.expire(partsKey(uploadId), Duration.ofHours(partStateTtlHours));
        redis.expire(bitmapKey(uploadId), Duration.ofHours(partStateTtlHours));
    }
}
