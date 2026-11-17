package com.sanhuan.emall.trade;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sanhuan.emall.api.ApiException;
import com.sanhuan.emall.auth.Session;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TradeService {

    private static final BigDecimal POINT_RATE = new BigDecimal("0.30");
    private static final int POINT_STEP = 100;
    private static final String REDIS_DOWN = "Redis 暂时连不上，请先放行 8.130.74.232:6379";
    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final JdbcTemplate jdbc;
    private final StringRedisTemplate redis;
    private final ObjectMapper json;
    private final MarketingService marketing;

    public TradeService(JdbcTemplate jdbc, StringRedisTemplate redis, ObjectMapper json, MarketingService marketing) {
        this.jdbc = jdbc;
        this.redis = redis;
        this.json = json;
        this.marketing = marketing;
    }

    public Map<String, Object> cart(long memberId) {
        List<Map<String, Object>> items = cartItems(memberId);
        Map<Long, Map<String, Object>> shops = new LinkedHashMap<>();
        int selectedQuantity = 0;
        int totalQuantity = 0;
        BigDecimal selectedAmount = BigDecimal.ZERO;
        for (Map<String, Object> item : items) {
            long shopId = ((Number) item.get("shopId")).longValue();
            Map<String, Object> shop = shops.computeIfAbsent(shopId, id -> {
                Map<String, Object> created = new LinkedHashMap<>();
                created.put("shopId", id);
                created.put("shopName", item.get("shopName"));
                created.put("items", new ArrayList<Map<String, Object>>());
                return created;
            });
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> lines = (List<Map<String, Object>>) shop.get("items");
            lines.add(item);
            int quantity = ((Number) item.get("quantity")).intValue();
            totalQuantity += quantity;
            if (Boolean.TRUE.equals(item.get("selected")) && !Boolean.TRUE.equals(item.get("invalid"))) {
                selectedQuantity += quantity;
                selectedAmount = selectedAmount.add(new BigDecimal(String.valueOf(item.get("amount"))));
            }
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("shops", new ArrayList<>(shops.values()));
        body.put("selectedQuantity", selectedQuantity);
        body.put("totalQuantity", totalQuantity);
        body.put("selectedAmount", money(selectedAmount));
        return body;
    }

    @Transactional
    public Map<String, Object> addItem(long memberId, Long skuId, Integer quantity) {
        if (skuId == null || quantity == null || quantity < 1) {
            throw new ApiException(400, "请选择规格和数量");
        }
        Priced priced = priceSku(skuId, quantity);
        long cartId = cartId(memberId, priced.shopId());
        List<Long> existing = jdbc.query(
                "SELECT id, quantity FROM shopping_cart_item WHERE cart_id = ? AND sku_id = ?",
                (rs, row) -> rs.getLong("id"),
                cartId,
                skuId
        );
        if (existing.isEmpty()) {
            jdbc.update(
                    "INSERT INTO shopping_cart_item (cart_id, sku_id, quantity, selected) VALUES (?, ?, ?, 1)",
                    cartId,
                    skuId,
                    Math.min(quantity, priced.stock())
            );
        } else {
            Integer current = jdbc.queryForObject(
                    "SELECT quantity FROM shopping_cart_item WHERE id = ?",
                    Integer.class,
                    existing.get(0)
            );
            int next = Math.min((current == null ? 0 : current) + quantity, priced.stock());
            jdbc.update("UPDATE shopping_cart_item SET quantity = ?, selected = 1 WHERE id = ?", next, existing.get(0));
        }
        return cart(memberId);
    }

    @Transactional
    public Map<String, Object> updateItem(long memberId, long itemId, Integer quantity, Boolean selected) {
        List<Long> owned = jdbc.query("""
                SELECT i.id
                FROM shopping_cart_item i
                JOIN shopping_cart c ON c.id = i.cart_id
                WHERE i.id = ? AND c.member_id = ?
                """, (rs, row) -> rs.getLong("id"), itemId, memberId);
        if (owned.isEmpty()) {
            throw new ApiException(404, "购物车里没有这件商品");
        }
        if (quantity != null && quantity <= 0) {
            jdbc.update("DELETE FROM shopping_cart_item WHERE id = ?", itemId);
            return cart(memberId);
        }
        if (quantity != null) {
            jdbc.update("UPDATE shopping_cart_item SET quantity = ? WHERE id = ?", quantity, itemId);
        }
        if (selected != null) {
            jdbc.update("UPDATE shopping_cart_item SET selected = ? WHERE id = ?", selected ? 1 : 0, itemId);
        }
        return cart(memberId);
    }

    @Transactional
    public Map<String, Object> selectAll(long memberId, boolean selected) {
        jdbc.update("""
                UPDATE shopping_cart_item i
                JOIN shopping_cart c ON c.id = i.cart_id
                SET i.selected = ?
                WHERE c.member_id = ?
                """, selected ? 1 : 0, memberId);
        return cart(memberId);
    }

    public Map<String, Object> quote(long memberId, Boolean usePoints, Long couponId, Long templateId, List<LineRequest> lines) {
        List<Priced> priced = resolve(memberId, lines);
        MarketingService.Deal deal = marketing.price(memberId, priced.get(0).shopId(), saleLines(priced), couponId, templateId);
        return quoteOf(memberId, Boolean.TRUE.equals(usePoints), priced, deal);
    }

    public List<Map<String, Object>> pickupPoints() {
        return jdbc.query("""
                SELECT id, point_name, address, manager_mobile, pickup_hours
                FROM pickup_point
                WHERE status = 'ENABLED'
                ORDER BY id
                """, (rs, row) -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", rs.getLong("id"));
            item.put("name", rs.getString("point_name"));
            item.put("address", rs.getString("address"));
            item.put("mobile", rs.getString("manager_mobile"));
            item.put("hours", rs.getString("pickup_hours"));
            return item;
        });
    }

    public List<Map<String, Object>> addresses(long memberId) {
        return jdbc.query("""
                SELECT id, receiver_name, receiver_mobile, province, city, district, detail_address, is_default
                FROM member_address
                WHERE member_id = ? AND status = 'ENABLED'
                ORDER BY is_default DESC, id DESC
                """, (rs, row) -> addressRow(rs), memberId);
    }

    @Transactional
    public Map<String, Object> saveAddress(long memberId, AddressRequest body) {
        String name = required(body.receiverName(), "请填写收货人");
        String mobile = required(body.receiverMobile(), "请填写手机号");
        if (!mobile.matches("1\\d{10}")) {
            throw new ApiException(400, "请填写11位手机号");
        }
        String detail = required(body.detailAddress(), "请填写详细地址");
        String province = required(body.province(), "请填写省");
        String city = required(body.city(), "请填写市");
        String district = required(body.district(), "请填写区");
        if (Boolean.TRUE.equals(body.asDefault())) {
            jdbc.update("UPDATE member_address SET is_default = 0 WHERE member_id = ?", memberId);
        }
        GeneratedKeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            var statement = connection.prepareStatement("""
                    INSERT INTO member_address
                    (member_id, receiver_name, receiver_mobile, province, city, district, detail_address, is_default)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                    """, new String[]{"id"});
            statement.setLong(1, memberId);
            statement.setString(2, name);
            statement.setString(3, mobile);
            statement.setString(4, province);
            statement.setString(5, city);
            statement.setString(6, district);
            statement.setString(7, detail);
            statement.setInt(8, Boolean.TRUE.equals(body.asDefault()) ? 1 : 0);
            return statement;
        }, keys);
        long id = keys.getKey().longValue();
        return jdbc.queryForObject("SELECT id, receiver_name, receiver_mobile, province, city, district, detail_address, is_default FROM member_address WHERE id = ?", (rs, row) -> addressRow(rs), id);
    }

    @Transactional
    public Map<String, Object> place(Session session, OrderRequest body) {
        List<Priced> lines = resolve(session.accountId(), body.lines());
        List<Long> skuIds = lines.stream().map(Priced::skuId).distinct().sorted().toList();
        List<String> locks = new ArrayList<>();
        try {
            for (Long skuId : skuIds) {
                if (!hold("emall:stock:" + skuId, Duration.ofSeconds(8))) {
                    throw new ApiException(409, "商品正在被其他顾客结算，请稍后再试");
                }
                locks.add("emall:stock:" + skuId);
            }
            return insertOrder(session.accountId(), body, lines);
        } finally {
            release(locks);
        }
    }

    @Transactional
    public Map<String, Object> pay(Session session, long orderId) {
        OrderHead order = lockOrder(session.accountId(), orderId);
        if ("PAID".equals(order.paymentStatus())) {
            return orderView(session.accountId(), orderId);
        }
        if (!"PENDING_PAY".equals(order.orderStatus())) {
            throw new ApiException(400, "这个订单不能支付");
        }
        String key = "emall:pay:" + orderId;
        if (!hold(key, Duration.ofDays(2))) {
            throw new ApiException(409, "支付正在处理，请稍后查看订单");
        }
        try {
            markPaid(session.accountId(), order);
            return orderView(session.accountId(), orderId);
        } catch (RuntimeException exception) {
            redis.delete(key);
            throw exception;
        }
    }

    @Transactional
    public Map<String, Object> cancel(Session session, long orderId) {
        OrderHead order = lockOrder(session.accountId(), orderId);
        if ("CANCELLED".equals(order.orderStatus())) {
            return orderView(session.accountId(), orderId);
        }
        if (!"PENDING_PAY".equals(order.orderStatus())) {
            throw new ApiException(400, "这个订单不能取消");
        }
        List<ItemQty> items = orderItems(orderId);
        for (ItemQty item : items) {
            Integer locked = jdbc.queryForObject(
                    "SELECT locked_qty FROM inventory_stock WHERE sku_id = ? FOR UPDATE",
                    Integer.class,
                    item.skuId()
            );
            if (locked == null || locked < item.quantity()) {
                throw new ApiException(409, "库存锁定状态异常");
            }
            Integer available = jdbc.queryForObject(
                    "SELECT available_qty FROM inventory_stock WHERE sku_id = ?",
                    Integer.class,
                    item.skuId()
            );
            int before = available == null ? 0 : available;
            jdbc.update(
                    "UPDATE inventory_stock SET available_qty = available_qty + ?, locked_qty = locked_qty - ? WHERE sku_id = ?",
                    item.quantity(),
                    item.quantity(),
                    item.skuId()
            );
            stockLog(item.skuId(), "UNLOCK", item.quantity(), before, before + item.quantity(), order.orderNo(), "取消订单释放库存");
        }
        jdbc.update(
                "UPDATE sales_order SET order_status = 'CANCELLED', cancelled_at = NOW() WHERE id = ?",
                orderId
        );
        marketing.release(orderId);
        statusLog(orderId, order.orderStatus(), "CANCELLED", "CANCEL", "会员取消", session.accountId());
        redis.delete("emall:pay:" + orderId);
        return orderView(session.accountId(), orderId);
    }

    public Map<String, Object> orderView(long memberId, long orderId) {
        List<Map<String, Object>> found = jdbc.query("""
                SELECT id, order_no, order_status, payment_status, fulfillment_mode, pickup_code, consignee_snapshot_json,
                       goods_amount, full_reduction_amount, coupon_amount, discount_amount, point_discount_amount, payable_amount,
                       remark, created_at, paid_at
                FROM sales_order
                WHERE id = ? AND member_id = ?
                """, (rs, row) -> orderMap(rs), orderId, memberId);
        if (found.isEmpty()) {
            throw new ApiException(404, "订单不存在");
        }
        Map<String, Object> order = found.get(0);
        order.put("items", jdbc.query("""
                SELECT product_name_snapshot, spec_snapshot_json, image_url_snapshot, quantity, item_amount
                FROM sales_order_item
                WHERE order_id = ?
                ORDER BY id
                """, (rs, row) -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("name", rs.getString("product_name_snapshot"));
            item.put("spec", specName(rs.getString("spec_snapshot_json")));
            item.put("imageUrl", rs.getString("image_url_snapshot"));
            item.put("quantity", rs.getInt("quantity"));
            item.put("amount", money(rs.getBigDecimal("item_amount")));
            return item;
        }, orderId));
        if ("COMPLETED".equals(order.get("status"))) {
            Integer gained = jdbc.queryForObject("""
                    SELECT COALESCE(SUM(change_points), 0) FROM point_ledger
                    WHERE member_id = ? AND change_type = 'ORDER_GAIN' AND related_no = ?
                    """, Integer.class, memberId, order.get("orderNo"));
            order.put("pointsGained", gained == null ? 0 : gained);
        }
        return order;
    }

    private Map<String, Object> insertOrder(long memberId, OrderRequest body, List<Priced> lines) {
        long shopId = lines.get(0).shopId();
        boolean express = "EXPRESS".equalsIgnoreCase(body.fulfillmentMode());
        String snapshot = express ? expressSnapshot(memberId, body.addressId()) : pickupSnapshot(body.pickupPointId());
        boolean usePoints = Boolean.TRUE.equals(body.usePoints());
        MarketingService.Deal deal = marketing.price(memberId, lines.get(0).shopId(), saleLines(lines), body.couponId(), body.templateId());
        Map<String, Object> quote = quoteOf(memberId, usePoints, lines, deal);
        BigDecimal pointAmount = new BigDecimal(String.valueOf(quote.get("pointAmount")));
        if (pointAmount.signum() > 0 && spendablePoints(memberId) / POINT_STEP < pointAmount.intValue()) {
            throw new ApiException(400, "积分不足");
        }
        for (Priced line : lines) {
            Integer available = jdbc.queryForObject(
                    "SELECT available_qty FROM inventory_stock WHERE sku_id = ? FOR UPDATE",
                    Integer.class,
                    line.skuId()
            );
            if (available == null || available < line.quantity()) {
                throw new ApiException(400, line.productName() + " 库存不足");
            }
            jdbc.update(
                    "UPDATE inventory_stock SET available_qty = available_qty - ?, locked_qty = locked_qty + ? WHERE sku_id = ? AND available_qty >= ?",
                    line.quantity(),
                    line.quantity(),
                    line.skuId(),
                    line.quantity()
            );
        }
        String orderNo = nextNo("SO");
        GeneratedKeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            var statement = connection.prepareStatement("""
                    INSERT INTO sales_order
                    (order_no, member_id, shop_id, pickup_point_id, fulfillment_mode, consignee_snapshot_json,
                     goods_amount, full_reduction_amount, coupon_amount, discount_amount, point_discount_amount,
                     freight_amount, payable_amount, order_status, payment_status, remark)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0, ?, 'PENDING_PAY', 'UNPAID', ?)
                    """, new String[]{"id"});
            statement.setString(1, orderNo);
            statement.setLong(2, memberId);
            statement.setLong(3, shopId);
            if (express) {
                statement.setNull(4, Types.BIGINT);
            } else {
                statement.setLong(4, pickupId(snapshot));
            }
            statement.setString(5, express ? "EXPRESS" : "SELF_PICKUP");
            statement.setString(6, snapshot);
            BigDecimal fullReduction = new BigDecimal(String.valueOf(quote.get("fullReductionAmount")));
            BigDecimal couponAmount = new BigDecimal(String.valueOf(quote.get("couponAmount")));
            BigDecimal activityAmount = new BigDecimal(String.valueOf(quote.get("activityAmount")));
            statement.setBigDecimal(7, new BigDecimal(String.valueOf(quote.get("goodsAmount"))));
            statement.setBigDecimal(8, fullReduction);
            statement.setBigDecimal(9, couponAmount);
            statement.setBigDecimal(10, activityAmount.add(fullReduction).add(couponAmount));
            statement.setBigDecimal(11, pointAmount);
            statement.setBigDecimal(12, new BigDecimal(String.valueOf(quote.get("payableAmount"))));
            statement.setString(13, blank(body.remark(), null));
            return statement;
        }, keys);
        long orderId = keys.getKey().longValue();
        for (Priced line : lines) {
            Integer before = jdbc.queryForObject(
                    "SELECT available_qty FROM inventory_stock WHERE sku_id = ?",
                    Integer.class,
                    line.skuId()
            );
            int after = before == null ? 0 : before;
            stockLog(line.skuId(), "LOCK", -line.quantity(), after + line.quantity(), after, orderNo, "下单锁定");
            jdbc.update("""
                    INSERT INTO sales_order_item
                    (order_id, spu_id, sku_id, product_name_snapshot, spec_snapshot_json, image_url_snapshot,
                     unit_name_snapshot, sale_price, quantity, item_amount)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, orderId, line.spuId(), line.skuId(), line.productName(), specJson(line, deal), line.imageUrl(),
                    line.unitName() == null ? "份" : line.unitName(), line.price(), line.quantity(),
                    line.price().multiply(BigDecimal.valueOf(line.quantity())));
        }
        marketing.consume(memberId, orderId, deal);
        statusLog(orderId, null, "PENDING_PAY", "PLACE", "会员下单", memberId);
        return orderView(memberId, orderId);
    }

    private void markPaid(long memberId, OrderHead order) {
        int pointYuan = order.pointAmount().setScale(0, RoundingMode.DOWN).intValue();
        if (pointYuan > 0) {
            spendPoints(memberId, pointYuan * POINT_STEP, order.orderNo());
        }
        List<ItemQty> items = orderItems(order.id());
        Map<Long, Integer> sold = new LinkedHashMap<>();
        for (ItemQty item : items) {
            Integer locked = jdbc.queryForObject(
                    "SELECT locked_qty FROM inventory_stock WHERE sku_id = ? FOR UPDATE",
                    Integer.class,
                    item.skuId()
            );
            if (locked == null || locked < item.quantity()) {
                throw new ApiException(409, "库存锁定状态异常");
            }
            Integer available = jdbc.queryForObject(
                    "SELECT available_qty FROM inventory_stock WHERE sku_id = ?",
                    Integer.class,
                    item.skuId()
            );
            int current = available == null ? 0 : available;
            jdbc.update(
                    "UPDATE inventory_stock SET locked_qty = locked_qty - ? WHERE sku_id = ? AND locked_qty >= ?",
                    item.quantity(),
                    item.skuId(),
                    item.quantity()
            );
            stockLog(item.skuId(), "PAY_DEDUCT", -item.quantity(), current, current, order.orderNo(), "支付扣减锁定库存");
            sold.merge(item.spuId(), item.quantity(), Integer::sum);
        }
        sold.forEach((spuId, quantity) -> jdbc.update(
                "UPDATE product_spu SET sold_count = sold_count + ? WHERE id = ?",
                quantity,
                spuId
        ));
        String paymentNo = nextNo("PY");
        jdbc.update("""
                INSERT INTO payment_record
                (payment_no, member_id, order_id, trade_type, pay_channel, amount, status, third_party_no, paid_at)
                VALUES (?, ?, ?, 'ORDER_PAY', 'WECHAT', ?, 'SUCCESS', ?, NOW())
                """, paymentNo, memberId, order.id(), order.payable(), "LOCAL-" + paymentNo);
        jdbc.update("""
                UPDATE sales_order
                SET order_status = 'PAID', payment_status = 'PAID', paid_amount = payable_amount, paid_at = NOW()
                WHERE id = ?
                """, order.id());
        jdbc.update("""
                DELETE i FROM shopping_cart_item i
                JOIN shopping_cart c ON c.id = i.cart_id
                WHERE c.member_id = ? AND i.sku_id IN (""" + marks(items.size()) + ")",
                args(memberId, items));
        statusLog(order.id(), "PENDING_PAY", "PAID", "PAY", "微信支付成功", memberId);
    }

    private List<Priced> resolve(long memberId, List<LineRequest> requested) {
        List<Priced> lines = new ArrayList<>();
        if (requested == null || requested.isEmpty()) {
            for (Map<String, Object> item : cartItems(memberId)) {
                if (!Boolean.TRUE.equals(item.get("selected")) || Boolean.TRUE.equals(item.get("invalid"))) {
                    continue;
                }
                lines.add(priceSku(((Number) item.get("skuId")).longValue(), ((Number) item.get("quantity")).intValue()));
            }
        } else {
            for (LineRequest line : requested) {
                if (line.skuId() == null || line.quantity() == null) {
                    throw new ApiException(400, "请选择规格和数量");
                }
                lines.add(priceSku(line.skuId(), line.quantity()));
            }
        }
        if (lines.isEmpty()) {
            throw new ApiException(400, "请选择可购买的商品");
        }
        long shopId = lines.get(0).shopId();
        if (lines.stream().anyMatch(line -> line.shopId() != shopId)) {
            throw new ApiException(400, "请按店铺分别结算");
        }
        return lines;
    }

    private Map<String, Object> quoteOf(long memberId, boolean usePoints, List<Priced> lines, MarketingService.Deal deal) {
        BigDecimal goods = new BigDecimal(deal.goods());
        BigDecimal activity = new BigDecimal(deal.activity());
        BigDecimal fullReduction = new BigDecimal(deal.fullReduction());
        BigDecimal coupon = new BigDecimal(deal.coupon());
        BigDecimal net = goods.subtract(activity).subtract(fullReduction).subtract(coupon).max(BigDecimal.ZERO);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Priced line : lines) {
            BigDecimal amount = line.price().multiply(BigDecimal.valueOf(line.quantity()));
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("skuId", line.skuId());
            row.put("name", line.productName());
            row.put("spec", line.skuName());
            row.put("quantity", line.quantity());
            row.put("imageUrl", line.imageUrl());
            row.put("amount", money(amount));
            row.put("price", money(line.price()));
            rows.add(row);
        }
        int balance = spendablePoints(memberId);
        int cap = net.multiply(POINT_RATE).setScale(0, RoundingMode.DOWN).intValue();
        int pointYuan = usePoints && balance >= POINT_STEP ? Math.min(balance / POINT_STEP, cap) : 0;
        BigDecimal pointAmount = BigDecimal.valueOf(pointYuan).setScale(2, RoundingMode.HALF_UP);
        Map<String, Object> quote = new LinkedHashMap<>();
        quote.put("shopName", lines.get(0).shopName());
        quote.put("lines", rows);
        quote.put("goodsAmount", money(goods));
        quote.put("activityAmount", money(activity));
        quote.put("activityName", deal.activityName());
        quote.put("fullReductionAmount", money(fullReduction));
        quote.put("fullReductionName", deal.fullReductionName());
        quote.put("couponAmount", money(coupon));
        quote.put("couponName", deal.couponName());
        quote.put("coupons", deal.coupons());
        quote.put("pointAmount", money(pointAmount));
        quote.put("payableAmount", money(net.subtract(pointAmount)));
        quote.put("pointsBalance", balance);
        quote.put("usePoints", usePoints && pointYuan > 0);
        quote.put("pointHint", balance < POINT_STEP ? "满100积分起用" : "100积分抵1元，最多抵30%");
        return quote;
    }

    private List<MarketingService.SaleLine> saleLines(List<Priced> lines) {
        return lines.stream()
                .map(line -> new MarketingService.SaleLine(line.skuId(), line.spuId(), line.categoryId(), line.price(), line.quantity()))
                .toList();
    }

    private Priced priceSku(long skuId, int quantity) {
        if (quantity < 1) {
            throw new ApiException(400, "数量至少为 1");
        }
        List<Priced> found = jdbc.query("""
                SELECT k.id, k.spu_id, k.sku_name, k.unit_name, k.sale_price, k.main_image_url,
                       s.shop_id, s.category_id, s.product_name, s.sales_status, shop.shop_name, st.available_qty,
                       (SELECT COUNT(*) FROM product_sku child WHERE child.parent_sku_id = k.id) child_count,
                       (SELECT file_url FROM file_asset WHERE biz_type = 'PRODUCT' AND biz_id = s.id ORDER BY sort_no, id LIMIT 1) cover_url
                FROM product_sku k
                JOIN product_spu s ON s.id = k.spu_id
                JOIN shop ON shop.id = s.shop_id
                LEFT JOIN inventory_stock st ON st.sku_id = k.id
                WHERE k.id = ? AND k.status = 'ENABLED'
                """, (rs, row) -> {
            if (!"ON_SALE".equals(rs.getString("sales_status")) || rs.getInt("child_count") > 0 || rs.getBigDecimal("sale_price") == null) {
                return null;
            }
            int stock = rs.getObject("available_qty") == null ? 0 : rs.getInt("available_qty");
            String image = rs.getString("main_image_url");
            if (image == null || image.isBlank()) {
                image = rs.getString("cover_url");
            }
            return new Priced(
                    rs.getLong("id"),
                    rs.getLong("spu_id"),
                    rs.getLong("shop_id"),
                    rs.getString("shop_name"),
                    rs.getString("product_name"),
                    rs.getString("sku_name"),
                    rs.getString("unit_name"),
                    rs.getBigDecimal("sale_price").setScale(2, RoundingMode.HALF_UP),
                    image,
                    stock,
                    rs.getLong("category_id")
            );
        }, skuId);
        if (found.isEmpty() || found.get(0) == null) {
            throw new ApiException(400, "规格不能购买");
        }
        Priced priced = found.get(0);
        if (quantity > priced.stock()) {
            throw new ApiException(400, priced.productName() + " 库存不足");
        }
        return priced.withQuantity(quantity);
    }

    private List<Map<String, Object>> cartItems(long memberId) {
        return jdbc.query("""
                SELECT i.id, i.quantity, i.selected, k.id sku_id, k.sku_name, k.unit_name, k.sale_price, k.market_price, k.main_image_url,
                       k.status sku_status, s.id spu_id, s.product_name, s.sales_status, s.shop_id, shop.shop_name, st.available_qty,
                       (SELECT COUNT(*) FROM product_sku child WHERE child.parent_sku_id = k.id) child_count,
                       (SELECT file_url FROM file_asset WHERE biz_type = 'PRODUCT' AND biz_id = s.id ORDER BY sort_no, id LIMIT 1) cover_url
                FROM shopping_cart_item i
                JOIN shopping_cart c ON c.id = i.cart_id
                JOIN product_sku k ON k.id = i.sku_id
                JOIN product_spu s ON s.id = k.spu_id
                JOIN shop ON shop.id = s.shop_id
                LEFT JOIN inventory_stock st ON st.sku_id = k.id
                WHERE c.member_id = ?
                ORDER BY i.id DESC
                """, (rs, row) -> {
            int stock = rs.getObject("available_qty") == null ? 0 : rs.getInt("available_qty");
            boolean invalid = !"ON_SALE".equals(rs.getString("sales_status"))
                    || !"ENABLED".equals(rs.getString("sku_status"))
                    || rs.getInt("child_count") > 0
                    || rs.getBigDecimal("sale_price") == null
                    || stock <= 0;
            BigDecimal price = rs.getBigDecimal("sale_price") == null
                    ? BigDecimal.ZERO
                    : rs.getBigDecimal("sale_price").setScale(2, RoundingMode.HALF_UP);
            int quantity = rs.getInt("quantity");
            String image = rs.getString("main_image_url");
            if (image == null || image.isBlank()) {
                image = rs.getString("cover_url");
            }
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", rs.getLong("id"));
            item.put("skuId", rs.getLong("sku_id"));
            item.put("spuId", rs.getLong("spu_id"));
            item.put("shopId", rs.getLong("shop_id"));
            item.put("shopName", rs.getString("shop_name"));
            item.put("name", rs.getString("product_name"));
            item.put("spec", rs.getString("sku_name"));
            item.put("unitName", rs.getString("unit_name"));
            item.put("price", money(price));
            BigDecimal market = rs.getBigDecimal("market_price");
            item.put("marketPrice", market != null && market.compareTo(price) > 0 ? money(market) : "");
            item.put("imageUrl", image);
            item.put("quantity", quantity);
            item.put("stock", stock);
            item.put("selected", rs.getInt("selected") == 1 && !invalid);
            item.put("invalid", invalid);
            item.put("amount", money(price.multiply(BigDecimal.valueOf(quantity))));
            return item;
        }, memberId);
    }

    private long cartId(long memberId, long shopId) {
        List<Long> found = jdbc.query(
                "SELECT id FROM shopping_cart WHERE member_id = ? AND shop_id = ?",
                (rs, row) -> rs.getLong("id"),
                memberId,
                shopId
        );
        if (!found.isEmpty()) {
            return found.get(0);
        }
        GeneratedKeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            var statement = connection.prepareStatement(
                    "INSERT INTO shopping_cart (member_id, shop_id) VALUES (?, ?)",
                    new String[]{"id"}
            );
            statement.setLong(1, memberId);
            statement.setLong(2, shopId);
            return statement;
        }, keys);
        return keys.getKey().longValue();
    }

    private String pickupSnapshot(Long pickupPointId) {
        String sql = """
                SELECT id, point_name, address, manager_mobile
                FROM pickup_point
                WHERE status = 'ENABLED'
                """;
        List<Object> args = new ArrayList<>();
        if (pickupPointId != null) {
            sql += " AND id = ?";
            args.add(pickupPointId);
        }
        sql += " ORDER BY id LIMIT 1";
        List<String> jsonText = jdbc.query(sql, (rs, row) -> writeJson(Map.of(
                "mode", "SELF_PICKUP",
                "pickupPointId", rs.getLong("id"),
                "name", rs.getString("point_name"),
                "phone", rs.getString("manager_mobile"),
                "address", rs.getString("address")
        )), args.toArray());
        if (jsonText.isEmpty()) {
            throw new ApiException(400, "请选择自提点");
        }
        return jsonText.get(0);
    }

    private String expressSnapshot(long memberId, Long addressId) {
        if (addressId == null) {
            throw new ApiException(400, "请先填写收货地址");
        }
        List<String> jsonText = jdbc.query("""
                SELECT receiver_name, receiver_mobile, province, city, district, detail_address
                FROM member_address
                WHERE id = ? AND member_id = ? AND status = 'ENABLED'
                """, (rs, row) -> writeJson(Map.of(
                "mode", "EXPRESS",
                "name", rs.getString("receiver_name"),
                "phone", rs.getString("receiver_mobile"),
                "address", rs.getString("province") + rs.getString("city") + rs.getString("district") + rs.getString("detail_address")
        )), addressId, memberId);
        if (jsonText.isEmpty()) {
            throw new ApiException(400, "收货地址不存在");
        }
        return jsonText.get(0);
    }

    private long pickupId(String snapshot) {
        try {
            return json.readTree(snapshot).path("pickupPointId").asLong();
        } catch (Exception exception) {
            throw new ApiException(400, "请选择自提点");
        }
    }

    private void spendPoints(long memberId, int points, String orderNo) {
        List<Lot> lots = new ArrayList<>();
        for (Lot lot : lots(memberId)) {
            if (lot.left > 0 && (lot.expire == null || lot.expire.isAfter(Instant.now()))) {
                lots.add(lot);
            }
        }
        lots.sort(Comparator.comparing((Lot lot) -> lot.expire == null ? Instant.MAX : lot.expire));
        int need = points;
        Map<String, Integer> used = new LinkedHashMap<>();
        for (Lot lot : lots) {
            if (need == 0) {
                break;
            }
            int take = Math.min(lot.left, need);
            used.merge(lot.bucket, take, Integer::sum);
            need -= take;
        }
        if (need > 0) {
            throw new ApiException(400, "积分不足，请取消后重新下单");
        }
        int after = lastPointsAfter(memberId);
        for (Map.Entry<String, Integer> entry : used.entrySet()) {
            after -= entry.getValue();
            jdbc.update("""
                    INSERT INTO point_ledger
                    (ledger_no, member_id, change_type, change_points, point_bucket, points_after, related_no, remark)
                    VALUES (?, ?, 'ORDER_USE', ?, ?, ?, ?, '下单抵现')
                    """, nextNo("PT"), memberId, -entry.getValue(), entry.getKey(), after, orderNo);
        }
    }

    private int spendablePoints(long memberId) {
        int sum = 0;
        Instant now = Instant.now();
        for (Lot lot : lots(memberId)) {
            if (lot.left > 0 && (lot.expire == null || lot.expire.isAfter(now))) {
                sum += lot.left;
            }
        }
        return sum;
    }

    private List<Lot> lots(long memberId) {
        List<Ledger> rows = jdbc.query("""
                SELECT change_points, point_bucket, expire_at, created_at
                FROM point_ledger
                WHERE member_id = ?
                ORDER BY id
                """, (rs, row) -> new Ledger(
                rs.getInt("change_points"),
                rs.getString("point_bucket"),
                rs.getTimestamp("expire_at") == null ? null : rs.getTimestamp("expire_at").toInstant(),
                rs.getTimestamp("created_at").toInstant()
        ), memberId);
        List<Lot> lots = new ArrayList<>();
        for (Ledger row : rows) {
            if (row.points > 0) {
                lots.add(new Lot(row.bucket, row.expire, row.points));
                continue;
            }
            int need = -row.points;
            List<Lot> available = lots.stream()
                    .filter(lot -> lot.left > 0 && (lot.expire == null || lot.expire.isAfter(row.created)))
                    .sorted(Comparator.comparing(lot -> lot.expire == null ? Instant.MAX : lot.expire))
                    .toList();
            for (Lot lot : available) {
                if (need == 0) {
                    break;
                }
                int take = Math.min(lot.left, need);
                lot.left -= take;
                need -= take;
            }
        }
        return lots;
    }

    private int lastPointsAfter(long memberId) {
        List<Integer> found = jdbc.query(
                "SELECT points_after FROM point_ledger WHERE member_id = ? ORDER BY id DESC LIMIT 1",
                (rs, row) -> rs.getInt("points_after"),
                memberId
        );
        return found.isEmpty() ? 0 : found.get(0);
    }

    private OrderHead lockOrder(long memberId, long orderId) {
        List<OrderHead> found = jdbc.query("""
                SELECT id, order_no, order_status, payment_status, point_discount_amount, payable_amount
                FROM sales_order
                WHERE id = ? AND member_id = ?
                FOR UPDATE
                """, (rs, row) -> new OrderHead(
                rs.getLong("id"),
                rs.getString("order_no"),
                rs.getString("order_status"),
                rs.getString("payment_status"),
                rs.getBigDecimal("point_discount_amount"),
                rs.getBigDecimal("payable_amount")
        ), orderId, memberId);
        if (found.isEmpty()) {
            throw new ApiException(404, "订单不存在");
        }
        return found.get(0);
    }

    private List<ItemQty> orderItems(long orderId) {
        return jdbc.query(
                "SELECT sku_id, spu_id, quantity FROM sales_order_item WHERE order_id = ?",
                (rs, row) -> new ItemQty(rs.getLong("sku_id"), rs.getLong("spu_id"), rs.getInt("quantity")),
                orderId
        );
    }

    private Map<String, Object> orderMap(ResultSet rs) throws SQLException {
        String status = rs.getString("order_status");
        String snapshot = rs.getString("consignee_snapshot_json");
        Map<String, Object> order = new LinkedHashMap<>();
        order.put("id", rs.getLong("id"));
        order.put("orderNo", rs.getString("order_no"));
        order.put("status", status);
        order.put("statusName", statusName(status));
        order.put("statusNote", statusNote(status));
        order.put("paymentStatus", rs.getString("payment_status"));
        order.put("fulfillmentMode", rs.getString("fulfillment_mode"));
        order.put("fulfillmentText", "EXPRESS".equals(rs.getString("fulfillment_mode")) ? "配送到家" : "到店自提");
        order.put("destinationName", jsonText(snapshot, "name"));
        order.put("destinationDetail", jsonText(snapshot, "address"));
        order.put("destinationPhone", jsonText(snapshot, "phone"));
        String pickupCode = rs.getString("pickup_code");
        order.put("pickupCode", pickupCode);
        order.put("pickupCodeText", pickupCode != null && pickupCode.length() == 6
                ? pickupCode.substring(0, 3) + " " + pickupCode.substring(3)
                : pickupCode);
        BigDecimal fullReduction = rs.getBigDecimal("full_reduction_amount");
        BigDecimal couponAmount = rs.getBigDecimal("coupon_amount");
        BigDecimal discountAmount = rs.getBigDecimal("discount_amount");
        BigDecimal activityAmount = discountAmount.subtract(fullReduction).subtract(couponAmount).max(BigDecimal.ZERO);
        order.put("goodsAmount", money(rs.getBigDecimal("goods_amount")));
        order.put("activityAmount", money(activityAmount));
        order.put("fullReductionAmount", money(fullReduction));
        order.put("couponAmount", money(couponAmount));
        order.put("pointAmount", money(rs.getBigDecimal("point_discount_amount")));
        order.put("payableAmount", money(rs.getBigDecimal("payable_amount")));
        order.put("remark", rs.getString("remark"));
        order.put("createdAt", clock(rs.getTimestamp("created_at")));
        order.put("paidAt", clock(rs.getTimestamp("paid_at")));
        return order;
    }

    private void stockLog(long skuId, String type, int change, int before, int after, String related, String remark) {
        jdbc.update("""
                INSERT INTO inventory_log
                (sku_id, change_type, change_qty, before_available_qty, after_available_qty, related_no, remark)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """, skuId, type, change, before, after, related, remark);
    }

    private void statusLog(long orderId, String from, String to, String action, String remark, long memberId) {
        jdbc.update("""
                INSERT INTO order_status_log
                (order_id, from_status, to_status, action, remark, operator_type, member_operator_id)
                VALUES (?, ?, ?, ?, ?, 'MEMBER', ?)
                """, orderId, from, to, action, remark, memberId);
    }

    private boolean hold(String key, Duration ttl) {
        try {
            return Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(key, "1", ttl));
        } catch (DataAccessException exception) {
            throw new ApiException(503, REDIS_DOWN);
        }
    }

    private void release(List<String> keys) {
        if (keys.isEmpty()) {
            return;
        }
        try {
            redis.delete(keys);
        } catch (DataAccessException exception) {
            throw new ApiException(503, REDIS_DOWN);
        }
    }

    private String specJson(Priced line, MarketingService.Deal deal) {
        Map<String, Object> spec = new LinkedHashMap<>();
        spec.put("name", line.skuName());
        spec.put("unit", line.unitName() == null ? "" : line.unitName());
        Long activityItemId = deal.seckillSkus().get(line.skuId());
        if (activityItemId != null) {
            spec.put("activityItemId", activityItemId);
        }
        return writeJson(spec);
    }

    private String specName(String raw) {
        try {
            String name = json.readTree(raw).path("name").asText("");
            return name.isBlank() ? "默认规格" : name;
        } catch (Exception exception) {
            return "默认规格";
        }
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

    private String writeJson(Map<String, ?> value) {
        try {
            return json.writeValueAsString(value);
        } catch (Exception exception) {
            throw new ApiException(500, "订单信息没有写进去");
        }
    }

    private static Map<String, Object> addressRow(ResultSet rs) throws SQLException {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", rs.getLong("id"));
        item.put("name", rs.getString("receiver_name"));
        item.put("phone", rs.getString("receiver_mobile"));
        item.put("province", rs.getString("province"));
        item.put("city", rs.getString("city"));
        item.put("district", rs.getString("district"));
        item.put("detail", rs.getString("detail_address"));
        item.put("region", rs.getString("province") + rs.getString("city") + rs.getString("district"));
        item.put("isDefault", rs.getInt("is_default") == 1);
        return item;
    }

    private static String marks(int size) {
        return "?,".repeat(size).substring(0, size * 2 - 1);
    }

    private static Object[] args(long memberId, List<ItemQty> items) {
        Object[] values = new Object[items.size() + 1];
        values[0] = memberId;
        for (int index = 0; index < items.size(); index++) {
            values[index + 1] = items.get(index).skuId();
        }
        return values;
    }

    private static String nextNo(String prefix) {
        return prefix + DateTimeFormatter.ofPattern("yyyyMMddHHmmss").format(LocalDateTime.now())
                + ThreadLocalRandom.current().nextInt(1000, 10000);
    }

    private static String money(BigDecimal value) {
        return (value == null ? BigDecimal.ZERO : value).setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    private static String clock(Timestamp value) {
        return value == null ? "" : value.toLocalDateTime().format(CLOCK);
    }

    private static String required(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new ApiException(400, message);
        }
        return value.trim();
    }

    private static String blank(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private static String statusName(String status) {
        return switch (status) {
            case "PENDING_PAY" -> "待付款";
            case "PAID" -> "待备货";
            case "PREPARING" -> "备货中";
            case "READY_PICKUP" -> "待自提";
            case "PICKED_UP" -> "已自提";
            case "COMPLETED" -> "已完成";
            case "CANCELLED" -> "已关闭";
            case "REFUNDING" -> "退款中";
            case "REFUNDED" -> "已退款";
            default -> status;
        };
    }

    private static String statusNote(String status) {
        return switch (status) {
            case "PENDING_PAY" -> "请尽快完成支付，库存将为您暂时保留";
            case "PAID" -> "支付成功，等待商家开始备货";
            case "PREPARING" -> "商家正在为您精心备货";
            case "READY_PICKUP" -> "商品已备好，请凭取件码到店自提";
            case "PICKED_UP" -> "已取货，确认收货后按实付获得消费积分";
            case "COMPLETED" -> "订单已完成，消费积分已到账";
            case "CANCELLED" -> "订单已关闭，库存已退回";
            default -> "订单服务正在为您持续保障";
        };
    }

    public record LineRequest(Long skuId, Integer quantity) {
    }

    public record OrderRequest(
            Boolean usePoints,
            String remark,
            String fulfillmentMode,
            Long pickupPointId,
            Long addressId,
            List<LineRequest> lines,
            Long couponId,
            Long templateId
    ) {
    }

    public record AddressRequest(
            String receiverName,
            String receiverMobile,
            String province,
            String city,
            String district,
            String detailAddress,
            Boolean asDefault
    ) {
    }

    private record Priced(
            long skuId,
            long spuId,
            long shopId,
            String shopName,
            String productName,
            String skuName,
            String unitName,
            BigDecimal price,
            String imageUrl,
            int stock,
            long categoryId,
            int quantity
    ) {
        private Priced(
                long skuId,
                long spuId,
                long shopId,
                String shopName,
                String productName,
                String skuName,
                String unitName,
                BigDecimal price,
                String imageUrl,
                int stock,
                long categoryId
        ) {
            this(skuId, spuId, shopId, shopName, productName, skuName, unitName, price, imageUrl, stock, categoryId, 0);
        }

        private Priced withQuantity(int quantity) {
            return new Priced(skuId, spuId, shopId, shopName, productName, skuName, unitName, price, imageUrl, stock, categoryId, quantity);
        }
    }

    private static final class Lot {
        private final String bucket;
        private final Instant expire;
        private int left;

        private Lot(String bucket, Instant expire, int left) {
            this.bucket = bucket;
            this.expire = expire;
            this.left = left;
        }
    }

    private record Ledger(int points, String bucket, Instant expire, Instant created) {
    }

    private record OrderHead(
            long id,
            String orderNo,
            String orderStatus,
            String paymentStatus,
            BigDecimal pointAmount,
            BigDecimal payable
    ) {
    }

    private record ItemQty(long skuId, long spuId, int quantity) {
    }
}
