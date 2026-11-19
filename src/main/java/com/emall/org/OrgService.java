package com.emall.org;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.emall.api.ApiException;
import com.emall.auth.AccessCatalog;
import com.emall.catalog.CatalogService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OrgService {

    public static final String INITIAL_PASSWORD = "123456";

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final PasswordEncoder passwordEncoder;
    private final CatalogService catalog;

    public OrgService(JdbcTemplate jdbc, ObjectMapper json, PasswordEncoder passwordEncoder, CatalogService catalog) {
        this.jdbc = jdbc;
        this.json = json;
        this.passwordEncoder = passwordEncoder;
        this.catalog = catalog;
    }

    public List<Map<String, Object>> platformMenus() {
        return AccessCatalog.tree(true);
    }

    public List<Map<String, Object>> merchantMenus() {
        return AccessCatalog.tree(false);
    }

    public List<Map<String, Object>> platformRoles() {
        return roles(true);
    }

    public List<Map<String, Object>> merchantRoles() {
        return roles(false);
    }

    @Transactional
    public Map<String, Object> savePlatformRole(Long id, RoleRequest request) {
        return saveRole(true, id, request);
    }

    @Transactional
    public Map<String, Object> saveMerchantRole(Long id, RoleRequest request) {
        return saveRole(false, id, request);
    }

    public List<Map<String, Object>> platformStaff() {
        return jdbc.query("""
                SELECT a.id, a.username, a.real_name, a.mobile, a.status, r.id AS role_id, r.role_name
                FROM platform_account a
                LEFT JOIN platform_account_role ar ON ar.platform_account_id = a.id
                LEFT JOIN platform_role r ON r.id = ar.role_id
                ORDER BY a.id
                """, (rs, row) -> staffRow(rs));
    }

    public List<Map<String, Object>> merchantStaff(long accountId) {
        long shopId = catalog.merchantShopId(accountId);
        return jdbc.query("""
                SELECT a.id, a.username, a.real_name, a.mobile, a.status, r.id AS role_id, r.role_name
                FROM merchant_account a
                LEFT JOIN merchant_account_role ar ON ar.merchant_account_id = a.id
                LEFT JOIN merchant_role r ON r.id = ar.role_id
                WHERE a.shop_id = ?
                ORDER BY a.id
                """, (rs, row) -> staffRow(rs), shopId);
    }

    @Transactional
    public Map<String, Object> savePlatformStaff(long actorId, Long id, StaffRequest request) {
        return saveStaff(true, actorId, id, request);
    }

    @Transactional
    public Map<String, Object> saveMerchantStaff(long actorId, Long id, StaffRequest request) {
        return saveStaff(false, actorId, id, request);
    }

    public List<Map<String, Object>> merchantAccounts() {
        return jdbc.query("""
                SELECT a.id, a.username, a.real_name, a.status, m.merchant_name, s.shop_name, r.role_name
                FROM merchant_account a
                JOIN merchant m ON m.id = a.merchant_id
                JOIN shop s ON s.id = a.shop_id
                LEFT JOIN merchant_account_role ar ON ar.merchant_account_id = a.id
                LEFT JOIN merchant_role r ON r.id = ar.role_id
                ORDER BY a.id DESC
                """, (rs, row) -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", rs.getLong("id"));
            item.put("merchant", rs.getString("merchant_name"));
            item.put("shop", rs.getString("shop_name"));
            item.put("owner", rs.getString("real_name"));
            item.put("login", rs.getString("username"));
            item.put("role", rs.getString("role_name"));
            item.put("status", statusName(rs.getString("status")));
            return item;
        });
    }

    @Transactional
    public Map<String, Object> openMerchantAccount(AccountRequest request) {
        String merchant = required(request == null ? null : request.merchant(), "请填写商户名称");
        String shop = required(request == null ? null : request.shop(), "请填写店铺名称");
        String owner = required(request == null ? null : request.owner(), "请填写负责人");
        String login = loginOf(request == null ? null : request.login());
        if (taken(login)) {
            throw new ApiException(400, "这个登录账号已被使用");
        }
        long roleId = merchantRoleId(request == null ? null : request.roleId(), request == null ? null : request.role());
        boolean enabled = enabled(request == null ? null : request.status());
        long merchantId = insertMerchant(merchant, owner);
        long shopId = insertShop(merchantId, shop, owner);
        long accountId = insertAccount(false, login, owner, phone(request == null ? null : request.phone(), false), enabled, merchantId, shopId);
        bindRole(false, accountId, roleId);
        Map<String, Object> created = new LinkedHashMap<>();
        created.put("id", accountId);
        created.put("login", login);
        created.put("initialPassword", INITIAL_PASSWORD);
        return created;
    }

    @Transactional
    public Map<String, Object> updateMerchantAccount(long id, AccountRequest request) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM merchant_account WHERE id = ?", Integer.class, id);
        if (count == null || count == 0) {
            throw new ApiException(404, "商家账号不存在");
        }
        long roleId = merchantRoleId(request == null ? null : request.roleId(), request == null ? null : request.role());
        jdbc.update("UPDATE merchant_account SET status = ? WHERE id = ?", enabled(request == null ? null : request.status()) ? "ENABLED" : "DISABLED", id);
        bindRole(false, id, roleId);
        return Map.of("id", id);
    }

    public Map<String, Object> store(long accountId) {
        long shopId = catalog.merchantShopId(accountId);
        return jdbc.queryForObject("""
                SELECT m.merchant_name, s.shop_name, s.contact_name, s.contact_mobile, s.address, s.open_hours, s.cutoff_time, s.status
                FROM shop s JOIN merchant m ON m.id = s.merchant_id WHERE s.id = ?
                """, (rs, row) -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("merchant", rs.getString("merchant_name"));
            item.put("shopName", rs.getString("shop_name"));
            item.put("contact", rs.getString("contact_name"));
            item.put("mobile", rs.getString("contact_mobile"));
            item.put("address", rs.getString("address"));
            item.put("hours", rs.getString("open_hours"));
            item.put("cutoff", cutoff(rs.getString("cutoff_time")));
            item.put("status", "OPEN".equals(rs.getString("status")) ? "营业中" : "休息");
            return item;
        }, shopId);
    }

    @Transactional
    public Map<String, Object> saveStore(long accountId, StoreRequest request) {
        long shopId = catalog.merchantShopId(accountId);
        String name = required(request == null ? null : request.shopName(), "请填写店铺名称");
        String address = required(request == null ? null : request.address(), "请填写地址");
        String hours = request == null || request.hours() == null ? "" : request.hours().trim();
        String cutoff = request == null || request.cutoff() == null ? "" : request.cutoff().trim();
        if (!cutoff.isEmpty() && !cutoff.matches("\\d{2}:\\d{2}")) {
            throw new ApiException(400, "截单时间格式应为 14:00");
        }
        boolean open = request == null || request.status() == null || "营业中".equals(request.status()) || "OPEN".equalsIgnoreCase(request.status());
        jdbc.update("""
                UPDATE shop
                SET shop_name = ?, contact_name = ?, contact_mobile = ?, address = ?, open_hours = ?, cutoff_time = ?, status = ?
                WHERE id = ?
                """, name, blank(request == null ? null : request.contact()), blank(request == null ? null : request.mobile()), address,
                hours.isEmpty() ? null : hours, cutoff.isEmpty() ? null : cutoff + ":00", open ? "OPEN" : "REST", shopId);
        return store(accountId);
    }

    private List<Map<String, Object>> roles(boolean platform) {
        String sql = platform ? """
                SELECT r.id, r.role_code, r.role_name, r.permission_json, r.status,
                       (SELECT COUNT(*) FROM platform_account_role ar WHERE ar.role_id = r.id) AS staff_count
                FROM platform_role r ORDER BY r.id
                """ : """
                SELECT r.id, r.role_code, r.role_name, r.permission_json, r.status,
                       (SELECT COUNT(*) FROM merchant_account_role ar WHERE ar.role_id = r.id) AS staff_count
                FROM merchant_role r ORDER BY r.id
                """;
        return jdbc.query(sql, (rs, row) -> {
            JsonNode permission = read(rs.getString("permission_json"));
            List<String> menus = new ArrayList<>();
            permission.path("menus").forEach(item -> menus.add(item.asText()));
            if (menus.remove("org")) {
                if (!menus.contains("org-roles")) {
                    menus.add("org-roles");
                }
                if (!menus.contains("org-staff")) {
                    menus.add("org-staff");
                }
            }
            Map<String, Object> role = new LinkedHashMap<>();
            role.put("id", rs.getLong("id"));
            role.put("code", rs.getString("role_code"));
            role.put("name", rs.getString("role_name"));
            role.put("system", permission.path("system").asBoolean(false));
            role.put("dataScope", permission.path("dataScope").asText("全部业务线"));
            role.put("menus", menus);
            role.put("menuCount", menus.contains("*") ? "全部" : String.valueOf(menus.size()));
            role.put("staffCount", rs.getInt("staff_count"));
            role.put("status", statusName(rs.getString("status")));
            return role;
        });
    }

    private Map<String, Object> saveRole(boolean platform, Long id, RoleRequest request) {
        String name = required(request == null ? null : request.name(), "请填写岗位名称");
        if (name.length() > 64) {
            throw new ApiException(400, "岗位名称过长");
        }
        String scope = request == null || request.dataScope() == null || request.dataScope().isBlank()
                ? "全部业务线" : request.dataScope().trim();
        if (platform && !AccessCatalog.SCOPES.contains(scope)) {
            throw new ApiException(400, "请选择数据范围");
        }
        boolean enabled = enabled(request == null ? null : request.status());
        List<String> menus = request == null || request.menus() == null ? List.of() : request.menus();
        if (id == null) {
            if (menus.isEmpty()) {
                menus = List.of(platform ? "overview" : "dashboard");
            }
            long created = insertRole(platform, name, scope, enabled, menus, false);
            return Map.of("id", created);
        }
        JsonNode current = permissionOf(platform, id);
        if (current.path("system").asBoolean(false)) {
            throw new ApiException(400, "系统内置岗位拥有全部菜单，不能修改");
        }
        if (menus.isEmpty()) {
            throw new ApiException(400, "请至少选择一个菜单");
        }
        jdbc.update(platform
                        ? "UPDATE platform_role SET role_name = ?, permission_json = ?, status = ? WHERE id = ?"
                        : "UPDATE merchant_role SET role_name = ?, permission_json = ?, status = ? WHERE id = ?",
                name, permission(platform, false, scope, menus), enabled ? "ENABLED" : "DISABLED", id);
        return Map.of("id", id);
    }

    private long insertRole(boolean platform, String name, String scope, boolean enabled, List<String> menus, boolean system) {
        String code = (platform ? "PR" : "MR") + System.currentTimeMillis();
        GeneratedKeyHolder keys = new GeneratedKeyHolder();
        String sql = platform
                ? "INSERT INTO platform_role (role_code, role_name, permission_json, status) VALUES (?, ?, ?, ?)"
                : "INSERT INTO merchant_role (role_code, role_name, permission_json, status) VALUES (?, ?, ?, ?)";
        jdbc.update(connection -> {
            var statement = connection.prepareStatement(sql, new String[]{"id"});
            statement.setString(1, code);
            statement.setString(2, name);
            statement.setString(3, permission(platform, system, scope, menus));
            statement.setString(4, enabled ? "ENABLED" : "DISABLED");
            return statement;
        }, keys);
        return keys.getKey().longValue();
    }

    private Map<String, Object> saveStaff(boolean platform, long actorId, Long id, StaffRequest request) {
        String name = required(request == null ? null : request.name(), "请填写姓名");
        String phone = phone(request == null ? null : request.phone(), false);
        long roleId = platform
                ? platformRoleId(request == null ? null : request.roleId())
                : merchantRoleId(request == null ? null : request.roleId(), null);
        boolean enabled = enabled(request == null ? null : request.status());
        if (id == null) {
            String login = loginOf(request == null ? null : request.login());
            if (taken(login)) {
                throw new ApiException(400, "这个登录账号已被使用");
            }
            Long merchantId = null;
            Long shopId = null;
            if (!platform) {
                shopId = catalog.merchantShopId(actorId);
                merchantId = jdbc.queryForObject("SELECT merchant_id FROM shop WHERE id = ?", Long.class, shopId);
            }
            long accountId = insertAccount(platform, login, name, phone, enabled, merchantId, shopId);
            bindRole(platform, accountId, roleId);
            return Map.of("id", accountId, "login", login, "initialPassword", INITIAL_PASSWORD);
        }
        if (id == actorId && !enabled) {
            throw new ApiException(400, "不能停用自己的账号");
        }
        ensureSameRealm(platform, actorId, id);
        if (!enabled && platform && lastSuperAdmin(id)) {
            throw new ApiException(400, "至少保留一个启用的超级管理员");
        }
        String table = platform ? "platform_account" : "merchant_account";
        jdbc.update("UPDATE " + table + " SET real_name = ?, mobile = ?, status = ? WHERE id = ?",
                name, phone, enabled ? "ENABLED" : "DISABLED", id);
        bindRole(platform, id, roleId);
        return Map.of("id", id);
    }

    private void ensureSameRealm(boolean platform, long actorId, long id) {
        if (platform) {
            Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM platform_account WHERE id = ?", Integer.class, id);
            if (count == null || count == 0) {
                throw new ApiException(404, "员工不存在");
            }
            return;
        }
        Long shopId = catalog.merchantShopId(actorId);
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM merchant_account WHERE id = ? AND shop_id = ?",
                Integer.class, id, shopId);
        if (count == null || count == 0) {
            throw new ApiException(404, "员工不存在");
        }
    }

    private boolean lastSuperAdmin(long disablingId) {
        Integer left = jdbc.queryForObject("""
                SELECT COUNT(*) FROM platform_account a
                JOIN platform_account_role ar ON ar.platform_account_id = a.id
                JOIN platform_role r ON r.id = ar.role_id
                WHERE a.status = 'ENABLED' AND a.id <> ? AND r.permission_json LIKE '%"system":true%'
                """, Integer.class, disablingId);
        Integer targetIsSuper = jdbc.queryForObject("""
                SELECT COUNT(*) FROM platform_account_role ar
                JOIN platform_role r ON r.id = ar.role_id
                WHERE ar.platform_account_id = ? AND r.permission_json LIKE '%"system":true%'
                """, Integer.class, disablingId);
        return targetIsSuper != null && targetIsSuper > 0 && (left == null || left == 0);
    }

    private long insertAccount(boolean platform, String login, String name, String phone, boolean enabled, Long merchantId, Long shopId) {
        String no = (platform ? "PA" : "MA") + System.currentTimeMillis();
        String hash = passwordEncoder.encode(INITIAL_PASSWORD);
        GeneratedKeyHolder keys = new GeneratedKeyHolder();
        String sql = platform
                ? "INSERT INTO platform_account (platform_account_no, username, password_hash, real_name, mobile, status) VALUES (?, ?, ?, ?, ?, ?)"
                : "INSERT INTO merchant_account (merchant_account_no, merchant_id, shop_id, username, password_hash, real_name, mobile, status) VALUES (?, ?, ?, ?, ?, ?, ?, ?)";
        jdbc.update(connection -> {
            var statement = connection.prepareStatement(sql, new String[]{"id"});
            if (platform) {
                statement.setString(1, no);
                statement.setString(2, login);
                statement.setString(3, hash);
                statement.setString(4, name);
                statement.setString(5, phone);
                statement.setString(6, enabled ? "ENABLED" : "DISABLED");
            } else {
                statement.setString(1, no);
                statement.setLong(2, merchantId);
                statement.setLong(3, shopId);
                statement.setString(4, login);
                statement.setString(5, hash);
                statement.setString(6, name);
                statement.setString(7, phone);
                statement.setString(8, enabled ? "ENABLED" : "DISABLED");
            }
            return statement;
        }, keys);
        return keys.getKey().longValue();
    }

    private long insertMerchant(String name, String owner) {
        String no = "M" + System.currentTimeMillis();
        GeneratedKeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            var statement = connection.prepareStatement(
                    "INSERT INTO merchant (merchant_no, merchant_name, legal_name, contact_mobile, entry_status) VALUES (?, ?, ?, '未填写', 'APPROVED')",
                    new String[]{"id"});
            statement.setString(1, no);
            statement.setString(2, name);
            statement.setString(3, owner);
            return statement;
        }, keys);
        return keys.getKey().longValue();
    }

    private long insertShop(long merchantId, String name, String owner) {
        String no = "S" + System.currentTimeMillis();
        GeneratedKeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            var statement = connection.prepareStatement("""
                    INSERT INTO shop (merchant_id, shop_no, shop_name, contact_name, address, status)
                    VALUES (?, ?, ?, ?, '待完善', 'OPEN')
                    """, new String[]{"id"});
            statement.setLong(1, merchantId);
            statement.setString(2, no);
            statement.setString(3, name);
            statement.setString(4, owner);
            return statement;
        }, keys);
        return keys.getKey().longValue();
    }

    private void bindRole(boolean platform, long accountId, long roleId) {
        if (platform) {
            jdbc.update("DELETE FROM platform_account_role WHERE platform_account_id = ?", accountId);
            jdbc.update("INSERT INTO platform_account_role (platform_account_id, role_id) VALUES (?, ?)", accountId, roleId);
        } else {
            jdbc.update("DELETE FROM merchant_account_role WHERE merchant_account_id = ?", accountId);
            jdbc.update("INSERT INTO merchant_account_role (merchant_account_id, role_id) VALUES (?, ?)", accountId, roleId);
        }
    }

    private long platformRoleId(Long roleId) {
        if (roleId == null) {
            throw new ApiException(400, "请选择岗位");
        }
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM platform_role WHERE id = ? AND status = 'ENABLED'", Integer.class, roleId);
        if (count == null || count == 0) {
            throw new ApiException(400, "岗位不存在或已停用");
        }
        return roleId;
    }

    private long merchantRoleId(Long roleId, String roleName) {
        if (roleId != null) {
            Integer count = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM merchant_role WHERE id = ? AND status = 'ENABLED'", Integer.class, roleId);
            if (count == null || count == 0) {
                throw new ApiException(400, "岗位不存在或已停用");
            }
            return roleId;
        }
        String name = required(roleName, "请选择岗位");
        List<Long> found = jdbc.query(
                "SELECT id FROM merchant_role WHERE role_name = ? AND status = 'ENABLED'",
                (rs, row) -> rs.getLong("id"), name);
        if (found.isEmpty()) {
            throw new ApiException(400, "岗位不存在或已停用");
        }
        return found.get(0);
    }

    private JsonNode permissionOf(boolean platform, long id) {
        List<String> found = jdbc.query(
                platform ? "SELECT permission_json FROM platform_role WHERE id = ?" : "SELECT permission_json FROM merchant_role WHERE id = ?",
                (rs, row) -> rs.getString(1), id);
        if (found.isEmpty()) {
            throw new ApiException(404, "岗位不存在");
        }
        return read(found.get(0));
    }

    private String permission(boolean platform, boolean system, String scope, List<String> menus) {
        if (!system) {
            Set<String> allowed = AccessCatalog.leaves(platform);
            for (String menu : menus) {
                if (!allowed.contains(menu)) {
                    throw new ApiException(400, "菜单不存在：" + menu);
                }
            }
        }
        ObjectNode node = json.createObjectNode();
        node.put("system", system);
        node.put("dataScope", scope);
        ArrayNode array = node.putArray("menus");
        (system ? List.of("*") : menus).forEach(array::add);
        return node.toString();
    }

    private boolean taken(String login) {
        Integer platform = jdbc.queryForObject("SELECT COUNT(*) FROM platform_account WHERE username = ?", Integer.class, login);
        Integer merchant = jdbc.queryForObject("SELECT COUNT(*) FROM merchant_account WHERE username = ?", Integer.class, login);
        Integer pickup = jdbc.queryForObject("SELECT COUNT(*) FROM pickup_point_account WHERE username = ?", Integer.class, login);
        return (platform != null && platform > 0) || (merchant != null && merchant > 0) || (pickup != null && pickup > 0);
    }

    private static Map<String, Object> staffRow(java.sql.ResultSet rs) throws java.sql.SQLException {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", rs.getLong("id"));
        item.put("login", rs.getString("username"));
        item.put("name", rs.getString("real_name"));
        item.put("phone", rs.getString("mobile"));
        item.put("roleId", rs.getObject("role_id") == null ? null : rs.getLong("role_id"));
        item.put("role", rs.getString("role_name"));
        item.put("status", statusName(rs.getString("status")));
        return item;
    }

    private JsonNode read(String text) {
        try {
            return json.readTree(text == null || text.isBlank() ? "{}" : text);
        } catch (Exception exception) {
            return json.createObjectNode();
        }
    }

    private static String loginOf(String login) {
        String value = required(login, "请填写登录账号");
        if (!value.matches("[A-Za-z][A-Za-z0-9_]{2,31}")) {
            throw new ApiException(400, "登录账号用字母开头，3 到 32 位");
        }
        return value;
    }

    private static String phone(String phone, boolean required) {
        if (phone == null || phone.isBlank()) {
            if (required) {
                throw new ApiException(400, "请填写手机号");
            }
            return null;
        }
        String value = phone.trim();
        if (!value.matches("1\\d{10}")) {
            throw new ApiException(400, "请填写 11 位手机号");
        }
        return value;
    }

    private static boolean enabled(String status) {
        if (status == null || status.isBlank() || "启用".equals(status) || "ENABLED".equalsIgnoreCase(status)) {
            return true;
        }
        if ("停用".equals(status) || "DISABLED".equalsIgnoreCase(status)) {
            return false;
        }
        throw new ApiException(400, "请选择启用或停用");
    }

    private static String statusName(String status) {
        return "DISABLED".equals(status) ? "停用" : "启用";
    }

    private static String required(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new ApiException(400, message);
        }
        return value.trim();
    }

    private static String blank(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String cutoff(String value) {
        return value == null || value.length() < 5 ? "" : value.substring(0, 5);
    }

    public record RoleRequest(String name, String dataScope, String status, List<String> menus) {
    }

    public record StaffRequest(String name, String login, String phone, Long roleId, String status) {
    }

    public record AccountRequest(String merchant, String shop, String owner, String login, String phone, Long roleId, String role, String status) {
    }

    public record StoreRequest(String shopName, String contact, String mobile, String address, String hours, String cutoff, String status) {
    }
}
