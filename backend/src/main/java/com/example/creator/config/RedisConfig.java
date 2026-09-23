package com.example.creator.config;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RedisConfig {
    @Bean(destroyMethod = "shutdown")
    RedissonClient redissonClient(@Value("${spring.data.redis.host}") String host,
                                  @Value("${spring.data.redis.port}") int port,
                                  @Value("${spring.data.redis.password}") String password) {
        var config = new Config();
        config.setThreads(2).setNettyThreads(2);
        config.useSingleServer().setAddress("redis://" + host + ":" + port)
                .setPassword(password).setConnectionMinimumIdleSize(1).setConnectionPoolSize(8)
                .setSubscriptionConnectionMinimumIdleSize(1).setSubscriptionConnectionPoolSize(2)
                .setConnectTimeout(3000).setTimeout(3000);
        return Redisson.create(config);
    }
}
