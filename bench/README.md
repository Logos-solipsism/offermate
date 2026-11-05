# bench —— 压测工具（纯 JDK，不需要装 JMeter）

这个目录是给「Q13：841ms→21ms 这些数字怎么来的」准备的：**两条命令就能跑出简历上的真实数字。**（已经跑过一轮，结果见 压测结果记录.md）。这两个数字来自 `/api/bench/apply-sync` 与 `apply-async` **对照接口**，它们刻意剥离了限流、幂等键与分布式锁，只隔离「要不要等外部调用」这一个变量。

## 第一步：把中间件和服务起起来（要用 bench 配置）

    cd offermate
    docker compose up -d                      # MySQL / Redis / RocketMQ / MinIO
    # 执行一次 server/src/main/resources/db/schema.sql 建表
    cd server
    mvn spring-boot:run -Dspring-boot.run.profiles=bench

bench 配置只是多注册了两个压测专用接口，**正常启动时不会暴露**。

## 第二步：批量造测试账号（工具自动注册 + 登录，不用手工弄）

    cd ../bench
    java -Dstdout.encoding=UTF-8 Bench.java --prepare 500

跑完会在当前目录生成 tokens.txt（500 个登录态）。

## 第三步：压两遍，抄数字

    # 现在的异步版
    java -Dstdout.encoding=UTF-8 Bench.java --path /api/bench/apply-async --concurrency 500 --requests 5000

    # 重构前的同步版（对照组）
    java -Dstdout.encoding=UTF-8 Bench.java --path /api/bench/apply-sync  --concurrency 500 --requests 5000

输出长这样（下面是**工具自测**的格式示例，不是你的项目数据）：

    === OfferMate benchmark ===
    target       : http://localhost:18080/api/bench/apply-async
    concurrency  : 500    requests: 5000    total: 12.3s    throughput: 406 req/s
    success/fail : 4998 / 2
    latency (ms) : P50 12   P90 38   P95 52   P99 118   max 402   avg 18

把两遍的 P95 / P99 抄下来，填进简历。

## 两个接口的差别（这就是对比的全部）

| 接口 | 做了什么 | 相当于 |
| --- | --- | --- |
| /api/bench/apply-sync | 落库 → **等**三方解析（默认 800ms）→ **等**写站内信 → 返回 | 重构前的同步写法 |
| /api/bench/apply-async | 落库 → 发消息 → 立刻返回 | 现在的异步写法 |

为了只测「等不等外部调用」这一个变量，bench 接口**刻意不做限流、幂等和分布式锁**（那些是 Redis 往返，量级只有毫秒）。

外部调用的耗时可调：改 application.yml 里的 offermate.mock.parser-latency-millis。

## 参数说明

| 参数 | 默认 | 说明 |
| --- | --- | --- |
| --base | http://localhost:18080 | 服务地址（本机 8080 被占用，项目默认跑在 18080） |
| --path | /api/bench/apply-async | 压哪个接口 |
| --concurrency | 500 | 并发数 |
| --requests | 5000 | 总请求数 |
| --jobs | 等于账号数 | 岗位 ID 轮转范围（保证「用户+岗位」组合不重复） |
| --tokens | tokens.txt | 登录态文件 |
| --prepare N | — | 注册并登录 N 个测试账号 |
| --selftest | — | 用内置模拟接口自测工具本身 |

## 注意事项（面试时也要能说出来）

1. **不要用同一个账号压**：会被限流和唯一约束挡住，测出来是假的。工具已经用「每个请求换一个用户 + 换一个岗位」规避了这一点。
2. **压测会往数据库写数据**：跑完想干净点就清一下 application 表。
3. **环境必须如实说明**：本机 + Docker 起的中间件，只能说明趋势，不代表线上。
4. **如果大量出现连接失败**：那是客户端或系统限制，不是服务端问题，把并发降到 200 再试。
5. **看 P95/P99，不要看平均**：平均值会被大量快请求掩盖掉尾部。
