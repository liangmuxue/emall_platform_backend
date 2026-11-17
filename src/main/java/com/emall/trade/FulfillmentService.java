package com.emall.trade;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.emall.api.ApiException;
import com.emall.catalog.CatalogService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class FulfillmentService {

    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final CatalogService catalog;
    private final TradeService trade;

    public FulfillmentService(JdbcTemplate jdbc, ObjectMapper json, CatalogService catalog, TradeService trade) {
        this.jdbc = jdbc;
        this.json = json;
        this.catalog = catalog;
        this.trade = trade;
    }

    public List<Map<String, Object>> merchantOrders(long accountId) {
        long shopId = catalog.merchantShopId(accountId);
        return jdbc.query("""
                SELECT o.id, o.order_no, o.member_id, o.order_status, o.fulfillment_mode, o.pickup_code,
                       o.payable_amount, o.consignee_snapshot_json, o.created_at, o.paid_at,
                       m.nickname, m.mobile,
                       (SELECT product_name_snapshot FROM sales_order_item WHERE order_id = o.id ORDER BY id LIMIT 1) goods_name,
                       (SELECT COALESCE(SUM(quantity), 0) FROM sales_order_item WHERE order_id = o.id) goods_qty
                FROM sales_order o
                JOIN member_account m ON m.id = o.member_id
                WHERE o.shop_id = ? AND o.payment_status = 'PAID'
                ORDER BY o.id DESC
                """, (rs, row) -> summary(rs), shopId);
    }

    public Map<String, Object> merchantOrder(long accountId, long orderId) {
        long shopId = catalog.merchantShopId(accountId);
        Map<String, Object> order = one("""
                SELECT o.id, o.order_no, o.member_id, o.order_status, o.fulfillment_mode, o.pickup_code,
                       o.payable_amount, o.goods_amount, o.full_reduction_amount, o.coupon_amount, o.discount_amount, o.point_discount_amount,
                       o.consignee_snapshot_json, o.created_at, o.paid_at, o.remark,
                       m.nickname, m.mobile,
                       (SELECT product_name_snapshot FROM sales_order_item WHERE order_id = o.id ORDER BY id LIMIT 1) goods_name,
                       (SELECT COALESCE(SUM(quantity), 0) FROM sales_order_item WHERE order_id = o.id) goods_qty
                FROM sales_order o
                JOIN member_account m ON m.id = o.member_id
                WHERE o.id = ? AND o.shop_id = ?
                """, orderId, shopId);
        order.put("items", items(orderId));
        return order;
    }

    @Transactional
    public Map<String, Object> prepare(long accountId, long orderId) {
        long shopId = catalog.merchantShopId(accountId);
        Head order = lockShop(shopId, orderId);
        requirePickup(order);
        if (!"PAID".equals(order.status())) {
            throw new ApiException(400, "当前订单不能开始备货");
        }
        jdbc.update("UPDATE sales_order SET order_status = 'PREPARING' WHERE id = ?", orderId);
        log(orderId, "PAID", "PREPARING", "START_PREPARE", "商家开始备货", "MERCHANT", accountId);
        return merchantOrder(accountId, orderId);
    }

    @Transactional
    public Map<String, Object> ready(long accountId, long orderId) {
        long shopId = catalog.merchantShopId(accountId);
        Head order = lockShop(shopId, orderId);
        requirePickup(order);
        if (order.pickupPointId() == null) {
            throw new ApiException(400, "订单还没有自提点");
        }
        if (!"PREPARING".equals(order.status())) {
            throw new ApiException(400, "请先开始备货");
        }
        String code = freshCode();
        jdbc.update("""
                UPDATE sales_order
                SET order_status = 'READY_PICKUP', pickup_code = ?, prepared_at = NOW()
                WHERE id = ?
                """, code, orderId);
        log(orderId, "PREPARING", "READY_PICKUP", "FINISH_PREPARE", "备货完成，取件码 " + pretty(code), "MERCHANT", accountId);
        return merchantOrder(accountId, orderId);
    }

    public Map<String, Object> pickupBoard(long accountId) {
        Point point = point(accountId);
        List<Map<String, Object>> orders = jdbc.query("""
                SELECT o.id, o.order_no, o.member_id, o.order_status, o.fulfillment_mode, o.pickup_code,
                       o.payable_amount, o.consignee_snapshot_json, o.created_at, o.paid_at,
                       m.nickname, m.mobile,
                       (SELECT product_name_snapshot FROM sales_order_item WHERE order_id = o.id ORDER BY id LIMIT 1) goods_name,
                       (SELECT COALESCE(SUM(quantity), 0) FROM sales_order_item WHERE order_id = o.id) goods_qty
                FROM sales_order o
                JOIN member_account m ON m.id = o.member_id
                WHERE o.pickup_point_id = ? AND o.order_status IN ('READY_PICKUP', 'PICKED_UP', 'COMPLETED')
                ORDER BY o.id DESC
                """, (rs, row) -> summary(rs), point.id());
        int waiting = 0;
        int picked = 0;
        for (Map<String, Object> order : orders) {
            String status = String.valueOf(order.get("status"));
            if ("READY_PICKUP".equals(status)) {
                waiting++;
            } else {
                picked++;
            }
        }
        Map<String, Object> board = new LinkedHashMap<>();
        board.put("pointName", point.name());
        board.put("address", point.address());
        board.put("waiting", waiting);
        board.put("picked", picked);
        board.put("orders", orders);
        return board;
    }

    public Map<String, Object> pickupOrder(long accountId, long orderId) {
        Point point = point(accountId);
        Map<String, Object> order = one("""
                SELECT o.id, o.order_no, o.member_id, o.order_status, o.fulfillment_mode, o.pickup_code,
                       o.payable_amount, o.goods_amount, o.full_reduction_amount, o.coupon_amount, o.discount_amount, o.point_discount_amount,
                       o.consignee_snapshot_json, o.created_at, o.paid_at, o.remark,
                       m.nickname, m.mobile,
                       (SELECT product_name_snapshot FROM sales_order_item WHERE order_id = o.id ORDER BY id LIMIT 1) goods_name,
                       (SELECT COALESCE(SUM(quantity), 0) FROM sales_order_item WHERE order_id = o.id) goods_qty
                FROM sales_order o
                JOIN member_account m ON m.id = o.member_id
                WHERE o.id = ? AND o.pickup_point_id = ?
                """, orderId, point.id());
        order.put("items", items(orderId));
        order.put("pointName", point.name());
        return order;
    }

    public Map<String, Object> lookup(long accountId, String code) {
        Point point = point(accountId);
        String digits = digits(code);
        if (digits.length() != 6) {
            throw new ApiException(400, "请输入 6 位取件码");
        }
        List<Long> found = jdbc.query("""
                SELECT id, order_status FROM sales_order
                WHERE pickup_point_id = ? AND pickup_code = ?
                ORDER BY id DESC
                """, (rs, row) -> {
            if (!"READY_PICKUP".equals(rs.getString("order_status"))) {
                throw new ApiException(400, "当前订单不处于待自提状态");
            }
            return rs.getLong("id");
        }, point.id(), digits);
        if (found.isEmpty()) {
            throw new ApiException(400, "提货码不正确");
        }
        return pickupOrder(accountId, found.get(0));
    }

    @Transactional
    public Map<String, Object> confirmPickup(long accountId, long orderId, String code) {
        Point point = point(accountId);
        String digits = digits(code);
        Head order = lockPickup(point.id(), orderId);
        if (!"READY_PICKUP".equals(order.status())) {
            throw new ApiException(400, "当前订单不处于待自提状态");
        }
        if (order.pickupCode() == null || !order.pickupCode().equals(digits)) {
            throw new ApiException(400, "提货码不正确");
        }
        jdbc.update("""
                UPDATE sales_order SET order_status = 'PICKED_UP', picked_up_at = NOW() WHERE id = ?
                """, orderId);
        log(orderId, "READY_PICKUP", "PICKED_UP", "PICKUP_CONFIRM", "自提点确认已自提", "PICKUP", accountId);
        return pickupOrder(accountId, orderId);
    }

    @Transactional
    public Map<String, Object> receive(long memberId, long orderId) {
        Head order = lockMember(memberId, orderId);
        if ("COMPLETED".equals(order.status())) {
            return trade.orderView(memberId, orderId);
        }
        if (!"PICKED_UP".equals(order.status())) {
            throw new ApiException(400, "请先到店取货，再确认收货");
        }
        jdbc.update("""
                UPDATE sales_order SET order_status = 'COMPLETED', completed_at = NOW() WHERE id = ?
                """, orderId);
        grant(memberId, order.orderNo(), order.payable());
        jdbc.update("""
                INSERT INTO order_status_log
                (order_id, from_status, to_status, action, remark, operator_type, member_operator_id)
                VALUES (?, 'PICKED_UP', 'COMPLETED', 'CONFIRM_RECEIPT', '会员确认收货', 'MEMBER', ?)
                """, orderId, memberId);
        return trade.orderView(memberId, orderId);
    }

    private void grant(long memberId, String orderNo, BigDecimal payable) {
        int points = payable.setScale(0, RoundingMode.DOWN).intValue();
        if (points <= 0) {
            return;
        }
        Integer exists = jdbc.queryForObject("""
                SELECT COUNT(*) FROM point_ledger
                WHERE member_id = ? AND change_type = 'ORDER_GAIN' AND related_no = ?
                """, Integer.class, memberId, orderNo);
        if (exists != null && exists > 0) {
            return;
        }
        List<Integer> last = jdbc.query(
                "SELECT points_after FROM point_ledger WHERE member_id = ? ORDER BY id DESC LIMIT 1",
                (rs, row) -> rs.getInt("points_after"),
                memberId
        );
        int after = (last.isEmpty() ? 0 : last.get(0)) + points;
        jdbc.update("""
                INSERT INTO point_ledger
                (ledger_no, member_id, change_type, change_points, point_bucket, points_after, related_no, remark)
                VALUES (?, ?, 'ORDER_GAIN', ?, 'BEHAVIOR', ?, ?, '确认收货获得消费积分')
                """, nextNo("PT"), memberId, points, after, orderNo);
    }

    private void requirePickup(Head order) {
        if (!"SELF_PICKUP".equals(order.mode())) {
            throw new ApiException(400, "配送订单的发货下一阶段再做");
        }
    }

    private String freshCode() {
        for (int attempt = 0; attempt < 8; attempt++) {
            String code = String.format("%06d", ThreadLocalRandom.current().nextInt(1_000_000));
            Integer used = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM sales_order WHERE pickup_code = ?",
                    Integer.class,
                    code
            );
            if (used != null && used == 0) {
                return code;
            }
        }
        throw new ApiException(500, "取件码生成失败，请重试");
    }

    private Head lockShop(long shopId, long orderId) {
        List<Head> found = jdbc.query("""
                SELECT id, order_no, member_id, pickup_point_id, fulfillment_mode, pickup_code, order_status, payable_amount
                FROM sales_order WHERE id = ? AND shop_id = ? FOR UPDATE
                """, (rs, row) -> head(rs), orderId, shopId);
        if (found.isEmpty()) {
            throw new ApiException(404, "订单不存在");
        }
        return found.get(0);
    }

    private Head lockPickup(long pointId, long orderId) {
        List<Head> found = jdbc.query("""
                SELECT id, order_no, member_id, pickup_point_id, fulfillment_mode, pickup_code, order_status, payable_amount
                FROM sales_order WHERE id = ? AND pickup_point_id = ? FOR UPDATE
                """, (rs, row) -> head(rs), orderId, pointId);
        if (found.isEmpty()) {
            throw new ApiException(404, "订单不存在");
        }
        return found.get(0);
    }

    private Head lockMember(long memberId, long orderId) {
        List<Head> found = jdbc.query("""
                SELECT id, order_no, member_id, pickup_point_id, fulfillment_mode, pickup_code, order_status, payable_amount
                FROM sales_order WHERE id = ? AND member_id = ? FOR UPDATE
                """, (rs, row) -> head(rs), orderId, memberId);
        if (found.isEmpty()) {
            throw new ApiException(404, "订单不存在");
        }
        return found.get(0);
    }

    private Map<String, Object> one(String sql, Object... args) {
        List<Map<String, Object>> found = jdbc.query(sql, (rs, row) -> summary(rs), args);
        if (found.isEmpty()) {
            throw new ApiException(404, "订单不存在");
        }
        return found.get(0);
    }

    private List<Map<String, Object>> items(long orderId) {
        return jdbc.query("""
                SELECT product_name_snapshot, spec_snapshot_json, image_url_snapshot, quantity, item_amount, sale_price
                FROM sales_order_item WHERE order_id = ? ORDER BY id
                """, (rs, row) -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("name", rs.getString("product_name_snapshot"));
            item.put("imageUrl", rs.getString("image_url_snapshot"));
            item.put("spec", specName(rs.getString("spec_snapshot_json")));
            item.put("quantity", rs.getInt("quantity"));
            item.put("price", money(rs.getBigDecimal("sale_price")));
            item.put("amount", money(rs.getBigDecimal("item_amount")));
            return item;
        }, orderId);
    }

    private Map<String, Object> summary(ResultSet rs) throws SQLException {
        String status = rs.getString("order_status");
        String mode = rs.getString("fulfillment_mode");
        String code = rs.getString("pickup_code");
        int qty = rs.getInt("goods_qty");
        String goodsName = rs.getString("goods_name");
        if (goodsName == null || goodsName.isBlank()) {
            goodsName = "商品";
        }
        Map<String, Object> order = new LinkedHashMap<>();
        order.put("id", rs.getLong("id"));
        order.put("orderNo", rs.getString("order_no"));
        order.put("status", status);
        order.put("statusName", statusName(status));
        order.put("fulfillmentMode", mode);
        order.put("fulfillmentText", "EXPRESS".equals(mode) ? "配送到家" : "到店自提");
        String snapshot = rs.getString("consignee_snapshot_json");
        order.put("buyer", rs.getString("nickname"));
        order.put("mobile", mask(rs.getString("mobile")));
        order.put("destinationName", jsonText(snapshot, "name"));
        order.put("destinationAddress", jsonText(snapshot, "address"));
        order.put("destinationPhone", jsonText(snapshot, "phone"));
        putAmount(order, rs, "goods_amount", "goodsAmount");
        putAmount(order, rs, "full_reduction_amount", "fullReductionAmount");
        putAmount(order, rs, "coupon_amount", "couponAmount");
        BigDecimal discount = optionalDecimal(rs, "discount_amount");
        BigDecimal full = optionalDecimal(rs, "full_reduction_amount");
        BigDecimal coupon = optionalDecimal(rs, "coupon_amount");
        if (discount != null && full != null && coupon != null) {
            order.put("activityAmount", money(discount.subtract(full).subtract(coupon).max(BigDecimal.ZERO)));
        }
        BigDecimal pointAmount = optionalDecimal(rs, "point_discount_amount");
        if (pointAmount != null) {
            order.put("pointAmount", money(pointAmount));
            order.put("pointsUsed", pointAmount.movePointRight(2).setScale(0, RoundingMode.DOWN).intValue());
        }
        String remark = optionalText(rs, "remark");
        if (remark != null) {
            order.put("remark", remark);
        }
        order.put("goods", qty > 1 ? goodsName + "等 " + qty + " 件" : goodsName);
        order.put("goodsQty", qty);
        order.put("payableAmount", money(rs.getBigDecimal("payable_amount")));
        order.put("pickupCode", code);
        order.put("pickupCodeText", pretty(code));
        order.put("createdAt", clock(rs.getTimestamp("created_at")));
        order.put("paidAt", clock(rs.getTimestamp("paid_at")));
        order.put("action", action(status, mode));
        order.put("actionName", actionName(status, mode));
        return order;
    }

    private static Head head(ResultSet rs) throws SQLException {
        long point = rs.getLong("pickup_point_id");
        boolean missingPoint = rs.wasNull();
        return new Head(
                rs.getLong("id"),
                rs.getString("order_no"),
                rs.getLong("member_id"),
                missingPoint ? null : point,
                rs.getString("fulfillment_mode"),
                rs.getString("pickup_code"),
                rs.getString("order_status"),
                rs.getBigDecimal("payable_amount")
        );
    }

    private Point point(long accountId) {
        List<Point> found = jdbc.query("""
                SELECT a.pickup_point_id, p.point_name, p.address
                FROM pickup_point_account a
                JOIN pickup_point p ON p.id = a.pickup_point_id
                WHERE a.id = ? AND a.status = 'ENABLED'
                """, (rs, row) -> new Point(rs.getLong("pickup_point_id"), rs.getString("point_name"), rs.getString("address")),
                accountId);
        if (found.isEmpty()) {
            throw new ApiException(403, "这个账号还没有绑定自提点");
        }
        return found.get(0);
    }

    private void log(long orderId, String from, String to, String action, String remark, String operator, long operatorId) {
        if ("MERCHANT".equals(operator)) {
            jdbc.update("""
                    INSERT INTO order_status_log
                    (order_id, from_status, to_status, action, remark, operator_type, merchant_operator_id)
                    VALUES (?, ?, ?, ?, ?, 'MERCHANT', ?)
                    """, orderId, from, to, action, remark, operatorId);
            return;
        }
        jdbc.update("""
                INSERT INTO order_status_log
                (order_id, from_status, to_status, action, remark, operator_type, pickup_operator_id)
                VALUES (?, ?, ?, ?, ?, 'PICKUP', ?)
                """, orderId, from, to, action, remark, operatorId);
    }

    private static String action(String status, String mode) {
        if (!"SELF_PICKUP".equals(mode)) {
            return "NONE";
        }
        return switch (status) {
            case "PAID" -> "PREPARE";
            case "PREPARING" -> "READY";
            default -> "NONE";
        };
    }

    private static String actionName(String status, String mode) {
        return switch (action(status, mode)) {
            case "PREPARE" -> "开始备货";
            case "READY" -> "备货完成";
            default -> "";
        };
    }

    private static String statusName(String status) {
        return switch (status) {
            case "PAID" -> "待备货";
            case "PREPARING" -> "备货中";
            case "READY_PICKUP" -> "待自提";
            case "PICKED_UP" -> "已自提";
            case "COMPLETED" -> "已完成";
            default -> status;
        };
    }

    private static String pretty(String code) {
        if (code == null || code.length() != 6) {
            return code == null ? "" : code;
        }
        return code.substring(0, 3) + " " + code.substring(3);
    }

    private static String digits(String code) {
        return code == null ? "" : code.replace(" ", "").trim();
    }

    private static String mask(String mobile) {
        if (mobile == null || mobile.length() < 7) {
            return mobile == null ? "" : mobile;
        }
        return mobile.substring(0, 3) + "****" + mobile.substring(mobile.length() - 4);
    }

    private static void putAmount(Map<String, Object> order, ResultSet rs, String column, String key) throws SQLException {
        BigDecimal value = optionalDecimal(rs, column);
        if (value != null) {
            order.put(key, money(value));
        }
    }

    private static BigDecimal optionalDecimal(ResultSet rs, String column) throws SQLException {
        if (!hasColumn(rs, column)) {
            return null;
        }
        return rs.getBigDecimal(column);
    }

    private static String optionalText(ResultSet rs, String column) throws SQLException {
        if (!hasColumn(rs, column)) {
            return null;
        }
        return rs.getString(column);
    }

    private static boolean hasColumn(ResultSet rs, String column) throws SQLException {
        var meta = rs.getMetaData();
        for (int index = 1; index <= meta.getColumnCount(); index++) {
            if (column.equalsIgnoreCase(meta.getColumnLabel(index))) {
                return true;
            }
        }
        return false;
    }

    private static String money(BigDecimal value) {
        return (value == null ? BigDecimal.ZERO : value).setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    private static String clock(Timestamp value) {
        return value == null ? "" : value.toLocalDateTime().format(CLOCK);
    }

    private static String nextNo(String prefix) {
        return prefix + DateTimeFormatter.ofPattern("yyyyMMddHHmmss").format(LocalDateTime.now())
                + ThreadLocalRandom.current().nextInt(1000, 10000);
    }

    private String jsonText(String raw, String field) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        try {
            return json.readTree(raw).path(field).asText("");
        } catch (Exception exception) {
            return "";
        }
    }

    private String specName(String raw) {
        try {
            String name = json.readTree(raw).path("name").asText("");
            return name.isBlank() ? "默认规格" : name;
        } catch (Exception exception) {
            return "默认规格";
        }
    }

    public record CodeBody(String code) {
    }

    private record Head(
            long id,
            String orderNo,
            long memberId,
            Long pickupPointId,
            String mode,
            String pickupCode,
            String status,
            BigDecimal payable
    ) {
    }

    private record Point(long id, String name, String address) {
    }
}
