package com.sanhuan.emall.auth;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;
import javax.crypto.SecretKey;

import com.sanhuan.emall.api.ApiException;
import com.sanhuan.emall.api.ClientKind;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class JwtService {

    private final SecretKey key;

    public JwtService(@Value("${emall.jwt-secret:}") String secret) {
        if (secret == null || secret.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalStateException("emall.jwt-secret 至少要 32 个字节，请写在 application-local.yml");
        }
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }

    public String issue(Session session, Instant expiresAt) {
        return Jwts.builder()
                .id(session.jti())
                .subject(Long.toString(session.accountId()))
                .claim("client", session.client().path())
                .claim("username", session.username())
                .claim("name", session.realName())
                .claim("avatar", session.avatar())
                .issuedAt(new Date())
                .expiration(Date.from(expiresAt))
                .signWith(key)
                .compact();
    }

    public Session parse(String token) {
        try {
            Claims claims = Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload();
            ClientKind client = ClientKind.from(claims.get("client", String.class));
            if (client == null || claims.getId() == null) {
                throw new ApiException(401, "登录已失效");
            }
            String avatar = claims.get("avatar", String.class);
            return new Session(
                    claims.getId(),
                    Long.parseLong(claims.getSubject()),
                    claims.get("username", String.class),
                    claims.get("name", String.class),
                    avatar == null ? "" : avatar,
                    client
            );
        } catch (ApiException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new ApiException(401, "登录已失效");
        }
    }

    public static String newJti() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}
