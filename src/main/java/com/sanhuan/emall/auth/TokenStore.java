package com.sanhuan.emall.auth;

import java.time.Duration;

import com.sanhuan.emall.api.ApiException;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

@Service
public class TokenStore {

    static final Duration TTL = Duration.ofHours(12);
    private static final String DOWN = "Redis 暂时连不上，登录票据发不出去。请先放行 8.130.74.232:6379";

    private final StringRedisTemplate redis;

    public TokenStore(StringRedisTemplate redis) {
        this.redis = redis;
    }

    public void save(Session session) {
        try {
            redis.opsForValue().set(key(session), Long.toString(session.accountId()), TTL);
        } catch (DataAccessException exception) {
            throw new ApiException(503, DOWN);
        }
    }

    public void remove(Session session) {
        try {
            redis.delete(key(session));
        } catch (DataAccessException exception) {
            throw new ApiException(503, DOWN);
        }
    }

    public void requireActive(Session session) {
        try {
            Boolean exists = redis.hasKey(key(session));
            if (!Boolean.TRUE.equals(exists)) {
                throw new ApiException(401, "登录已失效");
            }
        } catch (ApiException exception) {
            throw exception;
        } catch (DataAccessException exception) {
            throw new ApiException(503, DOWN);
        }
    }

    public String ping() {
        try {
            String reply = redis.execute((RedisCallback<String>) connection -> connection.ping());
            return reply == null ? "" : reply;
        } catch (RuntimeException exception) {
            return "";
        }
    }

    private static String key(Session session) {
        return "emall:session:" + session.client().path() + ":" + session.jti();
    }
}
