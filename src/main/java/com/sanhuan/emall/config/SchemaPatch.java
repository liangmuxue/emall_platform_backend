package com.sanhuan.emall.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
@Order(1)
public class SchemaPatch implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SchemaPatch.class);

    private final JdbcTemplate jdbc;

    public SchemaPatch(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void run(ApplicationArguments args) {
        addColumn(
                "point_ledger",
                "point_bucket",
                "ALTER TABLE point_ledger ADD COLUMN point_bucket VARCHAR(20) NOT NULL DEFAULT 'BEHAVIOR' COMMENT 'WELFARE福利/BEHAVIOR行为' AFTER change_type"
        );
        addColumn(
                "point_ledger",
                "expire_at",
                "ALTER TABLE point_ledger ADD COLUMN expire_at DATETIME NULL COMMENT '福利积分到期时间，行为积分可空' AFTER points_after"
        );
        addColumn(
                "sales_order",
                "full_reduction_amount",
                "ALTER TABLE sales_order ADD COLUMN full_reduction_amount DECIMAL(12,2) NOT NULL DEFAULT 0.00 COMMENT '满减金额' AFTER goods_amount"
        );
        addColumn(
                "sales_order",
                "coupon_amount",
                "ALTER TABLE sales_order ADD COLUMN coupon_amount DECIMAL(12,2) NOT NULL DEFAULT 0.00 COMMENT '优惠券金额' AFTER full_reduction_amount"
        );
        jdbc.update(
                "ALTER TABLE sales_order MODIFY discount_amount DECIMAL(12,2) NOT NULL DEFAULT 0.00 COMMENT '满减加优惠券，保留给旧查询'"
        );
        jdbc.update(
                "ALTER TABLE point_ledger MODIFY change_type VARCHAR(30) NOT NULL COMMENT 'SIGNIN/ORDER_GAIN/ORDER_USE/EXCHANGE/ADJUST/WELFARE_GRANT/REVIEW/REFUND'"
        );
        addColumn(
                "product_spu",
                "fake_sold_count",
                "ALTER TABLE product_spu ADD COLUMN fake_sold_count INT UNSIGNED NOT NULL DEFAULT 0 COMMENT '展示附加已售，顾客看到的已售等于真实销量加这个数' AFTER sold_count"
        );
        if (columnRequired("product_sku", "unit_name")) {
            jdbc.update(
                    "ALTER TABLE product_sku MODIFY unit_name VARCHAR(20) NULL COMMENT '可售规格的单位，分组规格为空'"
            );
            log.info("product_sku.unit_name now nullable");
        }
        if (columnRequired("product_sku", "sale_price")) {
            jdbc.update(
                    "ALTER TABLE product_sku MODIFY sale_price DECIMAL(12,2) NULL COMMENT '可售规格售价，分组规格为空'"
            );
            log.info("product_sku.sale_price now nullable");
        }
        jdbc.update("""
                DELETE st FROM inventory_stock st
                INNER JOIN product_sku parent ON parent.id = st.sku_id
                WHERE EXISTS (SELECT 1 FROM product_sku child WHERE child.parent_sku_id = parent.id)
                """);
        jdbc.update("""
                UPDATE product_sku parent
                JOIN (
                    SELECT DISTINCT parent_sku_id AS id FROM product_sku WHERE parent_sku_id IS NOT NULL
                ) grouped ON grouped.id = parent.id
                SET parent.unit_name = NULL,
                    parent.sale_price = NULL,
                    parent.market_price = NULL,
                    parent.member_price = NULL
                """);
        if (!indexExists("point_ledger", "idx_point_bucket_expire")) {
            jdbc.update(
                    "ALTER TABLE point_ledger ADD INDEX idx_point_bucket_expire (member_id, point_bucket, expire_at)"
            );
            log.info("added index point_ledger.idx_point_bucket_expire");
        }
    }

    private void addColumn(String table, String column, String sql) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? AND COLUMN_NAME = ?",
                Integer.class,
                table,
                column
        );
        if (count != null && count > 0) {
            return;
        }
        jdbc.update(sql);
        log.info("added column {}.{}", table, column);
    }

    private boolean columnRequired(String table, String column) {
        String nullable = jdbc.queryForObject(
                "SELECT IS_NULLABLE FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? AND COLUMN_NAME = ?",
                String.class,
                table,
                column
        );
        return nullable != null && !"YES".equalsIgnoreCase(nullable);
    }

    private boolean indexExists(String table, String index) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? AND INDEX_NAME = ?",
                Integer.class,
                table,
                index
        );
        return count != null && count > 0;
    }
}
