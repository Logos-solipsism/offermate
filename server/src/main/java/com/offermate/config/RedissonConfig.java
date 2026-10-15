package com.offermate.config;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Redisson 配置。
 * 说明：tryLock(waitTime, -1, unit) 会启用 WatchDog，默认每 10s 把锁续期到 30s，
 * 用于"简历合并/解析"这类可能超过锁租期的长耗时场景，避免锁提前释放导致并发重复处理。
 */
@Configuration
public class RedissonConfig {

    @Value("${redisson.address}")
    private String address;

    @Value("${redisson.password}")
    private String password;

    @Value("${redisson.database}")
    private int database;

    @Bean(destroyMethod = "shutdown")
    public RedissonClient redissonClient() {
        Config config = new Config();
        config.useSingleServer()
                .setAddress(address)
                .setPassword(password)
                .setDatabase(database)
                .setConnectionPoolSize(32)
                .setConnectionMinimumIdleSize(8)
                .setTimeout(3000);
        // 看门狗超时 = 锁租期，30s；后台任务每 1/3 续期一次
        config.setLockWatchdogTimeout(30_000L);
        return Redisson.create(config);
    }
}
