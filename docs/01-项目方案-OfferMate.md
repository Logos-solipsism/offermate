# OfferMate —— 智能招聘与人才库平台（项目方案）

## 1. 一句话定位
面向求职者与 HR 的双边招聘平台：**简历分片上传与秒传 → RocketMQ 异步解析与 JD 匹配 → 投递状态机 → 站内信/短信通知 → AI 助手（Function Calling）查询进度与推荐岗位**。

## 2. 业务定位
1. 招聘双边业务的技术矛盾很集中：大文件传输、长耗时任务、高并发写、三方 API 不稳定——这四个问题决定了本项目的技术重心。
2. 需求来自真实体感：简历重复投递、大附件弱网上传失败、HR 反馈慢，都是常见且具体的痛点。
3. 明确不碰推荐算法、分布式事务、Service Mesh 这类与本项目目标无关的方向，**技术深度集中在"上传 / 异步 / 去重 / 限流 / 重试 / 大模型工具调用"六个点**。

## 3. 业务闭环（完整，可演示）
角色：求职者 SEEKER、招聘方 HR、平台运营 ADMIN。

| 模块 | 功能 | 相关技术点 |
| --- | --- | --- |
| 认证与权限 | 注册/登录、JWT、Redis 存会话、RBAC 三角色、接口限流 | 令牌桶限流 |
| 职位域 | 条件检索与分页（**已实现**）；职位发布/编辑/上下架、运营审核（规划中） | 分页检索 |
| 简历域 | 分片上传、秒传、断点续传、异步解析、结构化档案、多版本 | 分片续传、去重、MQ |
| 投递域 | 投递/撤回、状态机（已投递→已查看→面试邀约→录用/不合适）、HR 反馈 | Redisson 锁、幂等 |
| 通知域 | 站内信、短信/邮件（三方 mock）、MQ 异步、失败重试与死信 | 指数退避、MQ |
| AI 域 | 查投递进度、按条件推荐岗位、生成面试准备清单、简历诊断 | Function Calling |
| 运营后台 | MQ 消费与死信看板、按业务 ID 重放（**已实现**）；用户/职位审核、限流参数可视化、漏斗统计（规划中） | 死信重放 |

**演示主线（3 分钟讲完）**：注册登录 → 选择简历文件上传（展示分片进度、暂停后刷新页面继续传）→ 同一份简历再传一次触发秒传 → 投递职位（展示超限 429）→ HR 收到站内信并改变状态 → 求职者用 AI 助手问"我投的 XX 公司到哪一步了？"→ 后台看到 MQ 消费记录与一条被重试修复的失败任务。

## 4. 技术栈
| 层 | 选型 |
| --- | --- |
| 后端 | SpringBoot 3.2 / JDK 17 / Spring Validation / Spring AOP |
| 存储 | MySQL 8（业务数据）、Redis 7（会话、锁、限流、分片状态、去重标记、AI 记忆）、MinIO（对象存储） |
| 消息 | RocketMQ 5（异步解耦、延时重试、死信队列） |
| 持久层 | MyBatis-Plus 3.5 |
| 分布式 | Redisson 3.27（分布式锁 + WatchDog 续期） |
| AI | LangChain4j + OpenAI 兼容协议（硅基流动 / DeepSeek / Qwen） |
| 前端 | Vue 3 + Vite（样式手写，未引入 UI 组件库与状态管理库；HTTP 用原生 fetch 封装） |
| 辅助 | JWT、Knife4j、Docker Compose |

## 5. 总体架构

    浏览器(Vue3)
        │  HTTPS / JWT
        ▼
    ┌──────────────────────────── Nginx ────────────────────────────┐
    │                                                               │
    ▼                                                               ▼
    业务服务 SpringBoot (无状态, 可水平扩展)
      ├── 认证模块 ──── JWT + Redis 会话
      ├── 限流切面 ──── Redis + Lua 令牌桶 (投递 / AI 接口)
      ├── 简历模块 ──── 分片上传 / 断点续传 / MD5 秒传 ──► MinIO
      │                    └─ Redisson 分布式锁(合并) + WatchDog 续期
      ├── 投递模块 ──── 幂等 + 去重 + 落库 + 发 MQ (主链路只做这些)
      ├── AI 模块  ──── LangChain4j AiServices + Function Calling
      └── 通知模块 ──── 站内信 + 三方短信/邮件
        │                        ▲
        │ produce                │ consume
        ▼                        │
    ┌──────────────── RocketMQ ─┴────────────────┐
    │ topic_resume_parse   简历解析/AI结构化(长耗时) │
    │ topic_job_match      JD 匹配打分               │
    │ topic_notify         站内信/短信/邮件           │
    │ %DLQ%cg_xxx          重试耗尽后的死信           │
    └──────────────────────────────────────────────┘
        │
        ▼
    MySQL(业务)  Redis(会话/锁/限流/分片状态/记忆)  MinIO(文件)

> 说明：**本地演示时前端由 Vite 开发服务器直连后端，上图的 Nginx 属于生产部署规划，仓库不包含其配置**。业务服务本身无状态，会话与锁都在 Redis，可水平扩展。

## 6. 六个技术点与代码落点

### 技术点 1：分片上传 + 断点续传
- **痛点**：简历/作品集动辄几十上百 MB，弱网/地铁里传一半断掉就要重传，用户直接流失。
- **方案**：前端按 5MB 切片，服务端生成 uploadId，每片先以独立对象落在 MinIO 的 chunks/{uploadId}/{partNumber}；
  Redis 用 **Bitmap 记录分片序号**（SETBIT/GETBIT）与 Hash 记录每片大小；合并时用 Redisson 锁串行化，再调 MinIO ComposeObject 在服务端拼接成最终对象，随后异步清理分片对象。
- **续传**：init 接口按 fileMd5 找到未完成的 uploadId，返回已上传分片列表，前端只补缺失分片。
- **关键类**：ResumeUploadController、ResumeUploadServiceImpl。
- **实测口径**：25MB 文件切成 5 片、传完 3 片后中断，重新 init 只补传第 4、5 片，合并结果与源文件一致——重传量等于未完成的部分（详见 bench/压测说明.md）。

### 技术点 2：RocketMQ 全链路异步化
- **痛点**：简历解析 + 大模型结构化 + JD 匹配打分是秒级到几十秒级操作，同步做会让投递接口 2~3s 才返回，且线程池被拖垮。
- **方案**：投递/上传主链路只做 **参数校验 → 幂等去重 → 落库 → 发消息**，其余全部投递到 MQ 异步消费。
- **实测口径**：同一套业务代码两个分支对比实测——单请求 **841ms → 21ms**；50 并发下吞吐 **50 → 275 req/s**，P99 **1.28s → 596ms**（本机 Windows + Docker 中间件，详见 bench/压测说明.md）。
- **进阶**：顺序消息保证同一求职者的投递事件有序；延时消息（level 3 = 10s）做阶梯重试。

### 技术点 3：Redisson + WatchDog 分布式锁 + MD5 内容级去重
- **痛点**：① 同一份简历反复上传，浪费存储与 AI Token；② 用户连点/脚本批量重复投递；③ 解析任务超过锁租期时锁提前释放，导致重复处理。
- **方案**：文件流算 MD5 作为**内容指纹**，resume_file.md5 建唯一索引 → 命中即"秒传"直接返回已有对象地址；
  投递链路用 Redisson 锁（lock:apply:{userId}:{jobId}）+ 数据库唯一索引双保险；
  锁 leaseTime 传 -1 **由 WatchDog 每 10s 自动续期**，避免长耗时解析期间锁过期。
- **实测口径**：同一账号对同一职位间隔 1.2 秒连续提交 6 次，数据库最终只有 1 条记录（拦截率 100%）；同一份文件重复上传 10 次，秒传命中 10/10、只触发 1 次解析。解析链路使用可注入故障的 Mock 实现，因此**不声明 Token 消耗的下降百分比**。

### 技术点 4：Redis + Lua 令牌桶限流
- **痛点**：恶意批量投递和被刷的 AI 问答接口会直接烧钱、打满线程池。
- **方案**：令牌桶算法用 **Lua 脚本原子执行**（读 tokens/ts → 按时间差补桶 → 扣减 → 回写 + 过期时间），避免 GET 再 SET 的并发超发；
  注解 RateLimit 通过 AOP 切入，支持 USER / IP / API 三个维度和接口级 capacity、rate 配置，超限抛 429；
  **阈值优先从 rate_limit_config 表读取（本地缓存 30 秒，运营改完最多 30 秒生效），注解上的值只是兜底默认值**，读表失败或配置停用时自动回退，不会因为配置表出问题而挡住业务。
- **降级**：Redis 不可用时切换到本机令牌桶（AtomicLong + CAS 实现，按单机 QPS 放行），宁可轻微超发也不熔断业务。
- **实测口径**：投递接口配置容量 5 / 每秒补 1 时，并发 10 次正好放行 5 次、其余返回 429；按正常节奏（每 1.2 秒 1 次）连续请求全部通过。

### 技术点 5：指数退避重试机制 + 幂等 + 死信队列
- **痛点**：三方简历解析、短信、大模型 API 都会抖动；盲目立即重试会放大流量（重试风暴）。
- **方案**：统一封装 ExponentialBackoffRetryer：第 n 次退避 = min(maxBackoff, base × 2^(n-1)) + 随机抖动（避免同一时刻集体重试）；
  消费端用 mq_consume_log(msg_id 唯一索引) 做幂等，重复消息直接丢弃；
  MQ 消费抛异常自动进入阶梯重试，超过 16 次进 %DLQ%，后台提供死信一键重放。
- **实测口径**：注入 30% 失败率时单次调用成功率 68.0%，经指数退避重试后最终 99.50%（200 次实测）；把失败率提到 50%，单次 48.5%、最终 93.00%。
- **边界说明**：同步阻塞 sleep 重试只适合百毫秒级调用；长任务重试一律走 MQ 延时消息，不占用业务线程（两者不可混用）。

### 技术点 6：LangChain4j + Function Calling + Redis 会话记忆
- **痛点**："我投的岗位怎么样了"这类问题需要查库，纯 Prompt 大模型会胡说。
- **方案**：AiServices 声明式接口 + @Tool 工具方法，把内部能力暴露给模型：
  queryApplicationStatus（查投递进度）、recommendJobs（按城市/薪资/技能推荐）、getJobDetail（职位详情）、diagnoseResume（简历诊断）。
  会话记忆由自研 **RedisChatMemoryStore** 承载（key = ai:memory:{userId}，存 JSON 化的 ChatMessage 列表，TTL 7 天），支持多轮上下文。
- **实现状态**：工具与记忆这条链路是真实的调用代码，需要自行配置 API Key；未配置时接口返回降级提示语，不影响其他功能。真机效果评测（工具调用准确率、Token 消耗等）尚未做。

## 7. 数据模型（核心表）

| 表 | 关键字段 | 索引/约束 |
| --- | --- | --- |
| sys_user | id, phone, password(BCrypt), role, nickname, status | uk_phone |
| job | id, hr_id, title, city, salary_min/max, skills, jd, status, audit_status | idx_city_status, idx_hr |
| resume | id, user_id, title, file_id, parsed_json, parse_status, is_default | idx_user |
| resume_file | id, md5, object_key, size, upload_id, status, user_id | **uk_md5_user**(md5, user_id)、idx_upload_id |
| application | id, user_id, job_id, resume_id, status, hr_feedback, apply_time | **uk_user_job**, idx_job_status |
| notification | id, user_id, type, title, content, read_flag | idx_user_read |
| mq_consume_log | id, msg_id, topic, status, retry_count | **uk_msg_id**(幂等) |
| ai_chat_log | id, user_id, question, answer, tool_calls, token_cost | idx_user_time（tool_calls 与 token_cost 字段已预留，采集逻辑待补） |

DDL 见 server/src/main/resources/db/schema.sql。

## 8. 关键流程

### 8.1 分片上传 + 秒传 + 断点续传
    前端                          服务端                           Redis / MinIO
     │ 1 计算文件 MD5(Worker 分片算)  │                                │
     │── POST /resume/upload/init ──►│ 查 resume_file.md5              │
     │                               │── GET resume_file by md5 ─────►│ MySQL
     │◄── {秒传命中, url} ───────────│  命中直接返回(不再上传)          │
     │                               │  未命中: 生成 uploadId(业务自建) │
     │                               │  落库 status=UPLOADING           │
     │◄── {uploadId, uploadedParts} ─│  写 upload:bitmap:{uploadId}    │
     │                               │                                │
     │ 2 并发 PUT /chunk (5MB/片) ──►│ PutObject → chunks/{id}/{n}     │
     │                               │── HSET upload:parts:{id} ─────►│ Redis
     │                               │── SETBIT upload:bitmap:{id} ──►│ Redis
     │◄── {partNumber, etag} ────────│                                │
     │  (网络中断 → 刷新页面)          │                                │
     │── POST /resume/upload/init ──►│ 找回未完成 uploadId + 已传分片   │
     │◄── {uploadedParts:[1,2,4]} ───│  前端只补 3,5,6...              │
     │                               │                                │
     │ 3 POST /resume/upload/merge ─►│ Redisson 锁 lock:merge:{md5}    │
     │                               │  (leaseTime=-1, WatchDog 续期)  │
     │                               │── ComposeObject 服务端拼接 ───►│ MinIO
     │                               │  写 resume_file(uk_md5 兜底)     │
     │                               │── 发 MQ topic_resume_parse ───►│ RocketMQ
     │◄── {fileId, parseStatus=解析中}│  清 Redis 分片状态               │

### 8.2 投递（主链路不等待任何外部调用，实测单请求 20ms）
    校验参数 → 令牌桶限流(429) → 幂等(Redis setnx + uk_user_job)
      → Redisson 锁(lock:apply:{userId}:{jobId}, WatchDog 续期)
      → 是否存在有效投递 / 简历内容 MD5 是否与上次完全相同(内容级去重)
      → INSERT application → 发送 MQ(topic_job_match, topic_notify) → 返回"投递成功"
    解析/匹配/通知在消费端完成，失败自动重试，超限进死信。

### 8.3 MQ 消费（幂等 + 重试 + 死信）
    收到消息 → INSERT mq_consume_log(msg_id) 唯一索引
      ├─ 重复键异常 → 直接 ACK（幂等丢弃）
      └─ 首次 → ExponentialBackoffRetryer 调三方解析/大模型
             ├─ 成功 → 更新 resume.parsed_json / 发通知
             └─ 失败 → 抛异常 → RocketMQ 阶梯重试 → 16 次后进 %DLQ% → 后台人工/定时重放

### 8.4 AI Function Calling
    用户: "我投的字节跳动到哪一步了"
      → AiAssistant(带 Redis 历史消息)
      → 模型决定调用 queryApplicationStatus(keyword="字节跳动")
      → 工具内查 MySQL 返回结构化结果
      → 模型组织自然语言回答 → 写回 Redis 记忆

## 9. 接口清单（与代码一致）

除注册登录外，所有接口都需要请求头 `Authorization: Bearer <token>`。

| 方法 | 路径 | 说明 | 相关技术点 |
| --- | --- | --- | --- |
| POST | /api/auth/register | 注册（SEEKER / HR / ADMIN） | BCrypt |
| POST | /api/auth/login | 登录签发 JWT + 会话落 Redis | 会话、登录按 IP 限流 |
| GET | /api/job/list | 职位检索（分页 / 关键词 / 城市，只返回在招且审核通过） | 分页检索 |
| GET | /api/job/{id} | 职位详情（浏览量 +1） | — |
| GET | /api/job/mine | HR：我发布的职位 | — |
| POST | /api/resume/upload/init | 初始化上传：秒传命中 / 断点续传探测 / 新建任务 | MD5 去重、分片、Bitmap |
| POST | /api/resume/upload/chunk | 上传分片（multipart，字段名 file） | 分片、Bitmap |
| POST | /api/resume/upload/merge | 合并分片并触发异步解析 | Redisson 锁、MQ |
| GET | /api/resume/mine | 我上传的简历：上传状态 + 解析状态 + 结构化字段 | 异步状态机 |
| POST | /api/application | 投递职位 | 限流 + 锁 + 幂等 + 去重 + MQ |
| GET | /api/application/mine | 我的投递列表（含匹配分） | MQ 异步算分 |
| GET | /api/application/job/{jobId} | HR：某职位的投递列表（仅职位所属 HR 可见） | 归属校验 |
| PUT | /api/application/{id}/status | HR 流转状态并异步通知候选人 | 状态机 + MQ |
| GET | /api/notification/mine | 我的站内信 | MQ 异步 |
| POST | /api/ai/chat | AI 助手对话 | Function Calling |
| DELETE | /api/ai/memory | 清空会话记忆 | Redis 记忆 |
| GET | /api/admin/mq/logs | MQ 消费记录 / 死信看板 | 重试监控 |
| POST | /api/admin/mq/replay/resume/{fileId} | 按业务 ID 重放解析任务 | 死信重放 |

## 10. 项目边界（明确不做什么）
| 不做的事 | 原因 | 替代做法 |
| --- | --- | --- |
| 推荐算法 / 向量召回 | 与项目目标无关，属于另一个技术栈 | 匹配分用技能标签重合率，重心放在异步算分链路 |
| 分布式事务（TCC / Seata） | 投递链路可接受最终一致 | 唯一索引 + 幂等表 + 补偿任务 |
| 分库分表 | 数据量不支撑，引入后只有成本 | 合理索引 + 分页 + 归档 |
| 多级缓存 / 布隆过滤器 | 同上 | 唯一索引兜底，未引入缓存层 |
| AI 写操作 | 模型输出不可信 | 工具只读，写操作全走确定性代码 |

## 11. 设计决策与取舍记录（FAQ）
1. **为什么用 RocketMQ 不用 Kafka？** 生态与运维成本；RocketMQ 原生支持事务消息、延时消息、消息轨迹，更贴合"投递事件 + 阶梯重试"场景。
2. **为什么用 Redisson 不用 SETNX？** 可重入、WatchDog 自动续期、锁续期避免业务超时误释放、API 更安全（tryLock/unlock 校验持有者）。
3. **幂等怎么保证？** 消费端 msg_id 唯一索引 + 业务唯一索引（uk_user_job）双保险；对外接口用请求号 idempotency-key。
4. **分片合并为什么加锁？** 防止用户重复点合并 / 多端并发合并，导致服务端拼接冲突、分片对象被重复清理等脏数据。
4.1 **为什么不用 S3 MultipartUpload？** minio-java 的公开 API 没有提供 multipart 的 Args 类，所以采用「分片对象 + ComposeObject 服务端拼接」，代价是峰值 2 倍存储与一次额外 IO；若换成 AWS SDK 的 S3Client（同样对接 MinIO）即可零拷贝拼接，这是本项目记录的优化项。
5. **Redis 挂了限流怎么办？** 降级为本机令牌桶（按单机 QPS 放行），宁可轻微超发也不熔断业务。
6. **一致性？** 目前靠 RocketMQ 自身可靠性 + 消费端幂等表（mq_consume_log）+ 死信重放保证；**本地消息表与定时补偿属于待补项**——发消息前进程崩溃的极端场景理论上会丢一条消息。
7. **Redis 内存不够？** 分片状态设 24h TTL、AI 记忆设 7 天 TTL 且窗口 20 条，限流桶与去重标记同样带过期时间，闲置后自动回收。
8. **秒传的安全性？** 原则是"哈希值用来加速、不用来鉴权"：命中指纹后校验记录归属——不是自己的记录只复用存储对象、新建属于自己的记录，同时比对文件大小防碰撞。没有做的是分片内容哈希校验。
9. **前端大文件 MD5 计算会卡住页面吗？** 用 spark-md5 按 5MB 分片增量计算，避免一次性把整个文件读进内存，页面上有独立的指纹计算进度条。计算仍在主线程，超大文件可以进一步挪到 Web Worker。
10. **AI 幻觉怎么办？** System Prompt 约束"只能基于工具结果回答"，工具层做权限过滤（只能查自己的投递），超范围返回"查不到"。
