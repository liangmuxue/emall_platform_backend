package com.sanhuan.emall.auth;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import com.sanhuan.emall.api.ApiException;
import com.sanhuan.emall.api.ClientKind;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
public class AuthService {

    private final JdbcTemplate jdbc;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final TokenStore tokenStore;

    public AuthService(
            JdbcTemplate jdbc,
            PasswordEncoder passwordEncoder,
            JwtService jwtService,
            TokenStore tokenStore
    ) {
        this.jdbc = jdbc;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.tokenStore = tokenStore;
    }

    public Map<String, String> login(ClientKind client, String username, String password, String mobile) {
        Session draft = switch (client) {
            case C -> loginMember(mobile);
            case MER, MERCHANT -> loginPassword(client, "merchant_account", username, password, true);
            case PICKUP -> loginPassword(client, "pickup_point_account", username, password, false);
            case PLATFORM -> loginPassword(client, "platform_account", username, password, true);
        };
        Session session = new Session(
                JwtService.newJti(),
                draft.accountId(),
                draft.username(),
                draft.realName(),
                draft.avatar(),
                client
        );
        String token = jwtService.issue(session, Instant.now().plus(TokenStore.TTL));
        tokenStore.save(session);
        return Map.of("accessToken", token);
    }

    public void logout(Session session) {
        tokenStore.remove(session);
    }

    public Map<String, Object> userInfo(Session session, String token) {
        String home = session.client() == ClientKind.C
                || session.client() == ClientKind.MER
                || session.client() == ClientKind.PICKUP
                ? ""
                : "/dashboard/analytics";
        return Map.of(
                "userId", Long.toString(session.accountId()),
                "username", session.username(),
                "realName", session.realName(),
                "avatar", session.avatar(),
                "roles", List.of(session.client().path()),
                "desc", session.client().path(),
                "homePath", home,
                "token", token
        );
    }

    private Session loginPassword(
            ClientKind client,
            String table,
            String username,
            String password,
            boolean avatarColumn
    ) {
        if (username == null || username.isBlank() || password == null || password.isBlank()) {
            throw new ApiException(400, "请填写账号和密码");
        }
        String avatarSelect = avatarColumn ? "avatar_url" : "NULL AS avatar_url";
        List<AccountRow> rows = jdbc.query(
                "SELECT id, username, password_hash, real_name, " + avatarSelect + ", status FROM " + table + " WHERE username = ?",
                (rs, rowNum) -> new AccountRow(
                        rs.getLong("id"),
                        rs.getString("username"),
                        rs.getString("password_hash"),
                        rs.getString("real_name"),
                        rs.getString("avatar_url"),
                        rs.getString("status")
                ),
                username.trim()
        );
        if (rows.isEmpty() || !passwordEncoder.matches(password, rows.get(0).passwordHash())) {
            throw new ApiException(401, "账号或密码不正确");
        }
        AccountRow account = rows.get(0);
        if (!"ENABLED".equals(account.status())) {
            throw new ApiException(403, "账号已停用");
        }
        jdbc.update("UPDATE " + table + " SET last_login_at = NOW() WHERE id = ?", account.id());
        return new Session("", account.id(), account.username(), account.realName(), blank(account.avatar()), client);
    }

    private Session loginMember(String mobile) {
        if (mobile == null || !mobile.matches("1\\d{10}")) {
            throw new ApiException(400, "请填写11位手机号");
        }
        if (!"13800000000".equals(mobile)) {
            throw new ApiException(403, "这一阶段只开放演示手机号 13800000000");
        }
        List<AccountRow> rows = jdbc.query(
                "SELECT id, mobile, nickname, avatar_url, status FROM member_account WHERE mobile = ?",
                (rs, rowNum) -> new AccountRow(
                        rs.getLong("id"),
                        rs.getString("mobile"),
                        "",
                        rs.getString("nickname"),
                        rs.getString("avatar_url"),
                        rs.getString("status")
                ),
                mobile
        );
        AccountRow account;
        if (rows.isEmpty()) {
            jdbc.update(
                    "INSERT INTO member_account (member_no, mobile, nickname, member_level, status) VALUES (?, ?, ?, '普通会员', 'ENABLED')",
                    "MB13800000000",
                    mobile,
                    "演示会员"
            );
            account = jdbc.queryForObject(
                    "SELECT id, mobile, nickname, avatar_url, status FROM member_account WHERE mobile = ?",
                    (rs, rowNum) -> new AccountRow(
                            rs.getLong("id"),
                            rs.getString("mobile"),
                            "",
                            rs.getString("nickname"),
                            rs.getString("avatar_url"),
                            rs.getString("status")
                    ),
                    mobile
            );
        } else {
            account = rows.get(0);
        }
        if (account == null || !"ENABLED".equals(account.status())) {
            throw new ApiException(403, "账号已停用");
        }
        jdbc.update("UPDATE member_account SET last_login_at = NOW() WHERE id = ?", account.id());
        return new Session("", account.id(), account.username(), account.realName(), blank(account.avatar()), ClientKind.C);
    }

    private static String blank(String value) {
        return value == null ? "" : value;
    }

    private record AccountRow(
            long id,
            String username,
            String passwordHash,
            String realName,
            String avatar,
            String status
    ) {
    }
}
