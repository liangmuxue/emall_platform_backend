package com.emall.pickup;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

import com.emall.api.ApiException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PickupAdminService {

    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final JdbcTemplate jdbc;
    private final PasswordEncoder passwordEncoder;

    public PickupAdminService(JdbcTemplate jdbc, PasswordEncoder passwordEncoder) {
        this.jdbc = jdbc;
        this.passwordEncoder = passwordEncoder;
    }

    public List<Map<String, Object>> points() {
        return jdbc.query("""
                SELECT p.id, p.point_no, p.point_name, p.manager_name, p.manager_mobile, p.address,
                       p.longitude, p.latitude, p.pickup_hours, p.status, a.id AS account_id, a.username
                FROM pickup_point p
                LEFT JOIN pickup_point_account a ON a.pickup_point_id = p.id
                ORDER BY p.id DESC
                """, (rs, row) -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", rs.getLong("id"));
            item.put("pointNo", rs.getString("point_no"));
            item.put("name", rs.getString("point_name"));
            item.put("manager", rs.getString("manager_name"));
            item.put("mobile", rs.getString("manager_mobile"));
            item.put("address", rs.getString("address"));
            item.put("longitude", rs.getBigDecimal("longitude"));
            item.put("latitude", rs.getBigDecimal("latitude"));
            item.put("hours", rs.getString("pickup_hours"));
            item.put("status", rs.getString("status"));
            item.put("statusName", pointStatusName(rs.getString("status")));
            long accountId = rs.getLong("account_id");
            item.put("accountId", rs.wasNull() ? null : accountId);
            item.put("accountLogin", rs.getString("username"));
            return item;
        });
    }

    public List<Map<String, Object>> accounts() {
        return jdbc.query("""
                SELECT a.id, a.pickup_point_id, p.point_name, a.username, a.real_name, a.mobile, a.status, a.last_login_at
                FROM pickup_point_account a
                JOIN pickup_point p ON p.id = a.pickup_point_id
                ORDER BY a.id DESC
                """, (rs, row) -> accountRow(rs));
    }

    @Transactional
    public Map<String, Object> savePoint(Long id, PointBody body) {
        String name = limited(required(body == null ? null : body.name(), "请填写自提点名称"), 128, "自提点名称不超过 128 个字");
        String manager = limited(required(body == null ? null : body.manager(), "请填写负责人"), 64, "负责人不超过 64 个字");
        String mobile = phone(body == null ? null : body.mobile());
        String address = limited(required(body == null ? null : body.address(), "请填写地址"), 255, "地址不超过 255 个字");
        String hours = optional(body == null ? null : body.hours(), 100, "营业时间不超过 100 个字");
        Double longitude = coordinate(body == null ? null : body.longitude(), -180, 180, "经度要在 -180 到 180 之间");
        Double latitude = coordinate(body == null ? null : body.latitude(), -90, 90, "纬度要在 -90 到 90 之间");
        String status = pointStatus(body == null ? null : body.status());
        if (id == null) {
            String no = "PP" + DateTimeFormatter.ofPattern("yyyyMMddHHmmss").format(LocalDateTime.now())
                    + ThreadLocalRandom.current().nextInt(1000, 10000);
            jdbc.update("""
                    INSERT INTO pickup_point
                    (point_no, point_name, manager_name, manager_mobile, address, longitude, latitude, pickup_hours, status)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, no, name, manager, mobile, address, longitude, latitude, hours, status);
            id = jdbc.queryForObject("SELECT id FROM pickup_point WHERE point_no = ?", Long.class, no);
        } else {
            requirePoint(id);
            jdbc.update("""
                    UPDATE pickup_point
                    SET point_name = ?, manager_name = ?, manager_mobile = ?, address = ?,
                        longitude = ?, latitude = ?, pickup_hours = ?, status = ?
                    WHERE id = ?
                    """, name, manager, mobile, address, longitude, latitude, hours, status, id);
        }
        return point(id);
    }

    @Transactional
    public Map<String, Object> saveAccount(Long id, AccountBody body) {
        long pointId = body == null || body.pickupPointId() == null ? 0 : body.pickupPointId();
        requirePoint(pointId);
        if (id != null) {
            requireAccount(id);
        }
        String person = limited(required(body.person(), "请填写负责人"), 64, "负责人不超过 64 个字");
        String mobile = id == null ? phone(body.phone()) : phoneOrKeep(id, body.phone());
        String status = accountStatus(body.status());
        if (id == null) {
            String login = loginOf(body.login());
            String password = password(body.password());
            if (taken(login)) {
                throw new ApiException(400, "登录账号已被占用");
            }
            if (accountOfPoint(pointId, null) != null) {
                throw new ApiException(400, "这个自提点已经有登录账号");
            }
            jdbc.update("""
                    INSERT INTO pickup_point_account (pickup_point_id, username, password_hash, real_name, mobile, status)
                    VALUES (?, ?, ?, ?, ?, ?)
                    """, pointId, login, passwordEncoder.encode(password), person, mobile, status);
            id = jdbc.queryForObject("SELECT id FROM pickup_point_account WHERE username = ?", Long.class, login);
        } else {
            if (accountOfPoint(pointId, id) != null) {
                throw new ApiException(400, "这个自提点已经有登录账号");
            }
            jdbc.update("""
                    UPDATE pickup_point_account
                    SET pickup_point_id = ?, real_name = ?, mobile = ?, status = ?
                    WHERE id = ?
                    """, pointId, person, mobile, status, id);
        }
        return account(id);
    }

    @Transactional
    public Map<String, Object> resetPassword(long id, String raw) {
        requireAccount(id);
        jdbc.update(
                "UPDATE pickup_point_account SET password_hash = ? WHERE id = ?",
                passwordEncoder.encode(password(raw)),
                id
        );
        return account(id);
    }

    private Map<String, Object> point(long id) {
        List<Map<String, Object>> found = points().stream().filter(item -> id == ((Number) item.get("id")).longValue()).toList();
        if (found.isEmpty()) {
            throw new ApiException(404, "自提点不存在");
        }
        return found.get(0);
    }

    private Map<String, Object> account(long id) {
        List<Map<String, Object>> found = jdbc.query("""
                SELECT a.id, a.pickup_point_id, p.point_name, a.username, a.real_name, a.mobile, a.status, a.last_login_at
                FROM pickup_point_account a
                JOIN pickup_point p ON p.id = a.pickup_point_id
                WHERE a.id = ?
                """, (rs, row) -> accountRow(rs), id);
        if (found.isEmpty()) {
            throw new ApiException(404, "登录账号不存在");
        }
        return found.get(0);
    }

    private Map<String, Object> accountRow(java.sql.ResultSet rs) throws java.sql.SQLException {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", rs.getLong("id"));
        item.put("pickupPointId", rs.getLong("pickup_point_id"));
        item.put("pickupName", rs.getString("point_name"));
        item.put("person", rs.getString("real_name"));
        item.put("phone", rs.getString("mobile"));
        item.put("login", rs.getString("username"));
        item.put("status", rs.getString("status"));
        item.put("statusName", accountStatusName(rs.getString("status")));
        Timestamp last = rs.getTimestamp("last_login_at");
        item.put("lastLoginAt", last == null ? "" : last.toLocalDateTime().format(CLOCK));
        return item;
    }

    private void requirePoint(long id) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM pickup_point WHERE id = ?", Integer.class, id);
        if (count == null || count == 0) {
            throw new ApiException(404, "自提点不存在");
        }
    }

    private void requireAccount(long id) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM pickup_point_account WHERE id = ?", Integer.class, id);
        if (count == null || count == 0) {
            throw new ApiException(404, "登录账号不存在");
        }
    }

    private Long accountOfPoint(long pointId, Long exceptId) {
        List<Long> found = jdbc.query(
                "SELECT id FROM pickup_point_account WHERE pickup_point_id = ?",
                (rs, row) -> rs.getLong("id"),
                pointId
        );
        for (Long foundId : found) {
            if (exceptId == null || foundId != exceptId) {
                return foundId;
            }
        }
        return null;
    }

    private boolean taken(String login) {
        Integer platform = jdbc.queryForObject("SELECT COUNT(*) FROM platform_account WHERE username = ?", Integer.class, login);
        Integer merchant = jdbc.queryForObject("SELECT COUNT(*) FROM merchant_account WHERE username = ?", Integer.class, login);
        Integer pickup = jdbc.queryForObject("SELECT COUNT(*) FROM pickup_point_account WHERE username = ?", Integer.class, login);
        return (platform != null && platform > 0) || (merchant != null && merchant > 0) || (pickup != null && pickup > 0);
    }

    private static String pointStatus(String status) {
        if (status == null || status.isBlank() || "营业中".equals(status) || "ENABLED".equalsIgnoreCase(status)) {
            return "ENABLED";
        }
        if ("休息中".equals(status) || "DISABLED".equalsIgnoreCase(status)) {
            return "DISABLED";
        }
        throw new ApiException(400, "请选择营业中或休息中");
    }

    private static String accountStatus(String status) {
        if (status == null || status.isBlank() || "启用".equals(status) || "ENABLED".equalsIgnoreCase(status)) {
            return "ENABLED";
        }
        if ("停用".equals(status) || "DISABLED".equalsIgnoreCase(status)) {
            return "DISABLED";
        }
        throw new ApiException(400, "请选择启用或停用");
    }

    private static String pointStatusName(String status) {
        return "DISABLED".equals(status) ? "休息中" : "营业中";
    }

    private static String accountStatusName(String status) {
        return "DISABLED".equals(status) ? "停用" : "启用";
    }

    private static String loginOf(String login) {
        String value = required(login, "请填写登录账号");
        if (!value.matches("[A-Za-z][A-Za-z0-9_]{2,31}")) {
            throw new ApiException(400, "登录账号用字母开头，3 到 32 位");
        }
        return value;
    }

    private static String password(String password) {
        String value = required(password, "请填写密码");
        if (value.length() < 6 || value.length() > 32) {
            throw new ApiException(400, "密码为 6 到 32 位");
        }
        return value;
    }

    private String phoneOrKeep(long id, String phone) {
        if (phone == null || phone.isBlank()) {
            return jdbc.queryForObject(
                    "SELECT mobile FROM pickup_point_account WHERE id = ?",
                    String.class,
                    id
            );
        }
        return phone(phone);
    }

    private static String phone(String phone) {
        String value = required(phone, "请填写手机号");
        if (!value.matches("1\\d{10}")) {
            throw new ApiException(400, "请填写 11 位手机号");
        }
        return value;
    }

    private static String required(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new ApiException(400, message);
        }
        return value.trim();
    }

    private static String limited(String value, int max, String message) {
        if (value.length() > max) {
            throw new ApiException(400, message);
        }
        return value;
    }

    private static String optional(String value, int max, String message) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String text = value.trim();
        if (text.length() > max) {
            throw new ApiException(400, message);
        }
        return text;
    }

    private static Double coordinate(Double value, double min, double max, String message) {
        if (value == null || value.isNaN()) {
            return null;
        }
        if (value < min || value > max) {
            throw new ApiException(400, message);
        }
        return value;
    }

    public record PointBody(
            String name,
            String manager,
            String mobile,
            String address,
            String hours,
            String status,
            Double longitude,
            Double latitude
    ) {
    }

    public record AccountBody(Long pickupPointId, String person, String phone, String login, String password, String status) {
    }
}
