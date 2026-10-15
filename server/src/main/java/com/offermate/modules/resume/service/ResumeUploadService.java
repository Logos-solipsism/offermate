package com.offermate.modules.resume.service;

import java.io.InputStream;
import java.util.List;

public interface ResumeUploadService {

    /** 初始化上传：优先秒传，其次断点续传，最后新建分片上传 */
    InitResult init(Long userId, String fileName, String fileMd5, long size);

    /** 上传单个分片 */
    ChunkResult uploadChunk(Long userId, String uploadId, int partNumber, long size, InputStream stream);

    /** 合并分片并触发异步解析 */
    MergeResult merge(Long userId, String uploadId, int totalParts);

    /** 查询某个 uploadId 已成功上传的分片号（用于续传探测） */
    List<Integer> uploadedParts(String uploadId);

    record InitResult(boolean instantUpload, String uploadId, String objectKey, Long fileId, List<Integer> uploadedParts) {
    }

    record ChunkResult(int partNumber, String etag) {
    }

    record MergeResult(Long fileId, String md5, String objectKey, String parseStatus) {
    }
}
