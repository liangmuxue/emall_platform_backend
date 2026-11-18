package com.emall.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@Order(2)
public class AccountSeed implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AccountSeed.class);
    private static final String PASSWORD = "123456";

    private final JdbcTemplate jdbc;
    private final PasswordEncoder passwordEncoder;

    public AccountSeed(JdbcTemplate jdbc, PasswordEncoder passwordEncoder) {
        this.jdbc = jdbc;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        String hash = passwordEncoder.encode(PASSWORD);
        seedPlatform(hash);
        seedMerchant(hash);
        seedPickup(hash);
    }

    private void seedPlatform(String hash) {
        ensureRole(true, "SUPER", "超级管理员", true, "全部业务线", "[\"*\"]");
        ensureRole(true, "OPS", "运营", false, "全部业务线", "[\"overview\",\"members\",\"shops\",\"categories\",\"audits\",\"campaigns\",\"stats\"]");
        ensureRole(true, "SERVICE", "客服", false, "全部业务线", "[\"overview\",\"service\",\"aftersales\",\"members\"]");
        ensureRole(true, "FINANCE", "财务", false, "全部业务线", "[\"overview\",\"finance\",\"stats\"]");
        ensureRole(true, "PICKUP", "自提管理", false, "全部业务线", "[\"overview\",\"pickups\"]");
        ensurePlatformAccount(hash, "PA0001", "admin", "周宁", "13800001001", "SUPER");
        ensurePlatformAccount(hash, "PA0002", "operator", "陈可", "13800001002", "OPS");
        ensurePlatformAccount(hash, "PA0003", "service", "赵敏", "13800001003", "SERVICE");
        ensurePlatformAccount(hash, "PA0004", "finance", "孙磊", "13800001004", "FINANCE");
    }

    private void ensurePlatformAccount(String hash, String no, String username, String name, String mobile, String roleCode) {
        Long id = firstId("SELECT id FROM platform_account WHERE username = '" + username + "'");
        if (id == null) {
            jdbc.update(
                    "INSERT INTO platform_account (platform_account_no, username, password_hash, real_name, mobile, status) VALUES (?, ?, ?, ?, ?, 'ENABLED')",
                    no, username, hash, name, mobile
            );
            id = firstId("SELECT id FROM platform_account WHERE username = '" + username + "'");
            log.info("seeded platform account {}", username);
        }
        bind(true, id, roleCode);
    }

    private void seedMerchant(String hash) {
        ensureRole(false, "ADMIN", "商户管理员", true, "全部业务线", "[\"*\"]");
        ensureRole(false, "MANAGER", "店长", false, "全部业务线", "[\"dashboard\",\"products\",\"categories\",\"orders\",\"aftersale\",\"marketing\",\"data\",\"settlement\",\"store\"]");
        ensureRole(false, "GOODS", "商品专员", false, "全部业务线", "[\"dashboard\",\"products\",\"categories\"]");
        ensureRole(false, "ORDER", "订单专员", false, "全部业务线", "[\"dashboard\",\"orders\",\"aftersale\"]");
        ensureRole(false, "FINANCE", "财务", false, "全部业务线", "[\"dashboard\",\"data\",\"settlement\"]");
        Long shopAccount = firstId("SELECT id FROM merchant_account WHERE username = 'shop'");
        Long merchantId;
        Long shopId;
        if (shopAccount == null) {
            merchantId = firstId("SELECT id FROM merchant WHERE merchant_no = 'M0001'");
            if (merchantId == null) {
                jdbc.update(
                        "INSERT INTO merchant (merchant_no, merchant_name, legal_name, contact_mobile, entry_status) VALUES ('M0001', '林慧的店', '林慧', '13800001001', 'APPROVED')"
                );
                merchantId = firstId("SELECT id FROM merchant WHERE merchant_no = 'M0001'");
            }
            shopId = firstId("SELECT id FROM shop WHERE shop_no = 'S0001'");
            if (shopId == null) {
                jdbc.update(
                        "INSERT INTO shop (merchant_id, shop_no, shop_name, contact_name, contact_mobile, address, status) VALUES (?, 'S0001', '林慧的店', '林慧', '13800001001', '演示地址', 'OPEN')",
                        merchantId
                );
                shopId = firstId("SELECT id FROM shop WHERE shop_no = 'S0001'");
            }
            jdbc.update(
                    "INSERT INTO merchant_account (merchant_account_no, merchant_id, shop_id, username, password_hash, real_name, mobile, status) VALUES ('MA0001', ?, ?, 'shop', ?, '林慧', '13800002001', 'ENABLED')",
                    merchantId, shopId, hash
            );
            shopAccount = firstId("SELECT id FROM merchant_account WHERE username = 'shop'");
            log.info("seeded merchant account shop");
        } else {
            merchantId = firstId("SELECT merchant_id FROM merchant_account WHERE id = " + shopAccount);
            shopId = firstId("SELECT shop_id FROM merchant_account WHERE id = " + shopAccount);
        }
        bind(false, shopAccount, "ADMIN");
        Long clerk = firstId("SELECT id FROM merchant_account WHERE username = 'wangqiang'");
        if (clerk == null && merchantId != null && shopId != null) {
            jdbc.update(
                    "INSERT INTO merchant_account (merchant_account_no, merchant_id, shop_id, username, password_hash, real_name, mobile, status) VALUES ('MA0002', ?, ?, 'wangqiang', ?, '王强', '13800002002', 'ENABLED')",
                    merchantId, shopId, hash
            );
            clerk = firstId("SELECT id FROM merchant_account WHERE username = 'wangqiang'");
            log.info("seeded merchant account wangqiang");
        }
        if (clerk != null) {
            bind(false, clerk, "ORDER");
        }
    }

    private void ensureRole(boolean platform, String code, String name, boolean system, String scope, String menus) {
        String table = platform ? "platform_role" : "merchant_role";
        Long id = firstId("SELECT id FROM " + table + " WHERE role_code = '" + code + "'");
        String permission = "{\"system\":" + system + ",\"dataScope\":\"" + scope + "\",\"menus\":" + menus + "}";
        if (id != null) {
            String current = jdbc.queryForObject("SELECT CAST(permission_json AS CHAR) FROM " + table + " WHERE id = ?", String.class, id);
            if (current != null && current.trim().startsWith("{")) {
                return;
            }
            jdbc.update("UPDATE " + table + " SET permission_json = ? WHERE id = ?", permission, id);
            return;
        }
        jdbc.update(
                "INSERT INTO " + table + " (role_code, role_name, permission_json, status) VALUES (?, ?, ?, 'ENABLED')",
                code, name, permission
        );
    }

    private void bind(boolean platform, Long accountId, String roleCode) {
        if (accountId == null) {
            return;
        }
        String roleTable = platform ? "platform_role" : "merchant_role";
        String link = platform ? "platform_account_role" : "merchant_account_role";
        String accountColumn = platform ? "platform_account_id" : "merchant_account_id";
        Long roleId = firstId("SELECT id FROM " + roleTable + " WHERE role_code = '" + roleCode + "'");
        if (roleId == null) {
            return;
        }
        Integer linked = jdbc.queryForObject(
                "SELECT COUNT(*) FROM " + link + " WHERE " + accountColumn + " = ?",
                Integer.class,
                accountId
        );
        if (linked != null && linked > 0) {
            return;
        }
        jdbc.update("INSERT INTO " + link + " (" + accountColumn + ", role_id) VALUES (?, ?)", accountId, roleId);
    }

    private void seedPickup(String hash) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM pickup_point_account WHERE username = 'pickup'",
                Integer.class
        );
        if (count != null && count > 0) {
            return;
        }
        Long pointId = firstId("SELECT id FROM pickup_point WHERE point_no = 'PP0001'");
        if (pointId == null) {
            jdbc.update(
                    "INSERT INTO pickup_point (point_no, point_name, manager_name, manager_mobile, address, status) VALUES ('PP0001', '演示自提点', '自提点', '13800001002', '演示地址', 'ENABLED')"
            );
            pointId = firstId("SELECT id FROM pickup_point WHERE point_no = 'PP0001'");
        }
        jdbc.update(
                "INSERT INTO pickup_point_account (pickup_point_id, username, password_hash, real_name, status) VALUES (?, 'pickup', ?, '自提点', 'ENABLED')",
                pointId,
                hash
        );
        log.info("seeded pickup account pickup");
    }

    private Long firstId(String sql) {
        return jdbc.query(sql, rs -> rs.next() ? rs.getLong(1) : null);
    }
}
