package com.emall.config;

import java.time.Duration;

import io.lettuce.core.ClientOptions;
import io.lettuce.core.SocketOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.data.redis.LettuceClientConfigurationBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Configuration
@EnableScheduling
public class RedisConfig {

    @Bean
    LettuceClientConfigurationBuilderCustomizer redisConnectionCare() {
        SocketOptions.KeepAliveOptions keepAlive = SocketOptions.KeepAliveOptions.builder()
                .enable()
                .idle(Duration.ofSeconds(30))
                .interval(Duration.ofSeconds(10))
                .count(3)
                .build();
        ClientOptions options = ClientOptions.builder()
                .autoReconnect(true)
                .pingBeforeActivateConnection(true)
                .socketOptions(SocketOptions.builder()
                        .connectTimeout(Duration.ofSeconds(2))
                        .keepAlive(keepAlive)
                        .build())
                .build();
        return builder -> builder.clientOptions(options);
    }
}

@Component
class RedisHeartbeat {

    private static final Logger log = LoggerFactory.getLogger(RedisHeartbeat.class);

    private final StringRedisTemplate redis;

    RedisHeartbeat(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @Scheduled(fixedRate = 60_000, initialDelay = 20_000)
    public void ping() {
        try {
            redis.execute((RedisCallback<String>) connection -> connection.ping());
        } catch (RuntimeException exception) {
            log.warn("Redis 心跳没有得到回应，客户端会自动重连");
        }
    }
}
