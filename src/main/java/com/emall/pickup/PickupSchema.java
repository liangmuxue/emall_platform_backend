package com.emall.pickup;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
@Order(3)
public class PickupSchema implements ApplicationRunner {

    private final JdbcTemplate jdbc;

    public PickupSchema(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!hasColumn("longitude")) {
            jdbc.execute("ALTER TABLE pickup_point ADD COLUMN longitude DECIMAL(10,7) NULL AFTER address");
        }
        if (!hasColumn("latitude")) {
            jdbc.execute("ALTER TABLE pickup_point ADD COLUMN latitude DECIMAL(10,7) NULL AFTER longitude");
        }
        jdbc.update("""
                UPDATE pickup_point
                SET longitude = 121.5272000, latitude = 38.8586000
                WHERE point_name = '动漫游大厦自提点' AND longitude IS NULL AND latitude IS NULL
                """);
        jdbc.update("""
                UPDATE pickup_point
                SET longitude = 121.5450000, latitude = 38.8690000
                WHERE point_name = '演示自提点' AND longitude IS NULL AND latitude IS NULL
                """);
    }

    private boolean hasColumn(String name) {
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM information_schema.COLUMNS
                WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'pickup_point' AND COLUMN_NAME = ?
                """, Integer.class, name);
        return count != null && count > 0;
    }
}
