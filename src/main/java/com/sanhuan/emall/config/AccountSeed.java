package com.sanhuan.emall.config;

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
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM platform_account WHERE username = 'admin'",
                Integer.class
        );
        if (count != null && count > 0) {
            return;
        }
        jdbc.update(
                "INSERT INTO platform_account (platform_account_no, username, password_hash, real_name, status) VALUES ('PA0001', 'admin', ?, '周宁', 'ENABLED')",
                hash
        );
        log.info("seeded platform account admin");
    }

    private void seedMerchant(String hash) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM merchant_account WHERE username = 'shop'",
                Integer.class
        );
        if (count != null && count > 0) {
            return;
        }
        Long merchantId = firstId("SELECT id FROM merchant WHERE merchant_no = 'M0001'");
        if (merchantId == null) {
            jdbc.update(
                    "INSERT INTO merchant (merchant_no, merchant_name, legal_name, contact_mobile, entry_status) VALUES ('M0001', '林慧的店', '林慧', '13800001001', 'APPROVED')"
            );
            merchantId = firstId("SELECT id FROM merchant WHERE merchant_no = 'M0001'");
        }
        Long shopId = firstId("SELECT id FROM shop WHERE shop_no = 'S0001'");
        if (shopId == null) {
            jdbc.update(
                    "INSERT INTO shop (merchant_id, shop_no, shop_name, contact_name, contact_mobile, address, status) VALUES (?, 'S0001', '林慧的店', '林慧', '13800001001', '演示地址', 'OPEN')",
                    merchantId
            );
            shopId = firstId("SELECT id FROM shop WHERE shop_no = 'S0001'");
        }
        jdbc.update(
                "INSERT INTO merchant_account (merchant_account_no, merchant_id, shop_id, username, password_hash, real_name, status) VALUES ('MA0001', ?, ?, 'shop', ?, '林慧', 'ENABLED')",
                merchantId,
                shopId,
                hash
        );
        log.info("seeded merchant account shop");
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
