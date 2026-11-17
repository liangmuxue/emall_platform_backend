package com.sanhuan.emall.trade;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sanhuan.emall.api.ApiException;
import com.sanhuan.emall.catalog.CatalogService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MarketingService {

    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final CatalogService catalog;

    public MarketingService(JdbcTemplate jdbc, ObjectMapper json, CatalogService catalog) {
        this.jdbc = jdbc;
        this.json = json;
        this.catalog = catalog;
    }

    public List<Map<String, Object>> merchantActivities(long accountId) {
        long shopId = catalog.merchantShopId(accountId);
        return jdbc.query("""
                SELECT a.id, a.activity_no, a.activity_name, a.activity_type, a.product_scope_type, a.rule_json,
                       a.start_at, a.end_at, a.status, a.review_remark, shop.shop_name
                FROM marketing_activity a
                JOIN shop ON shop.id = a.shop_id
                WHERE a.shop_id = ?
                ORDER BY a.id DESC
                """, (rs, row) -> summary(rs), shopId);
    }

    public Map<String, Object> merchantActivity(long accountId, long id) {
        long shopId = catalog.merchantShopId(accountId);
        return detail(id, shopId);
    }

    public Map<String, Object> options(long accountId) {
        long shopId = catalog.merchantShopId(accountId);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("categories", jdbc.query("""
                SELECT id, category_name FROM product_category WHERE status = 'ENABLED' ORDER BY sort_no, id
                """, (rs, row) -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", rs.getLong("id"));
            item.put("name", rs.getString("category_name"));
            return item;
        }));
        List<Map<String, Object>> products = new ArrayList<>();
        List<Map<String, Object>> leaves = jdbc.query("""
                SELECT s.id spu_id, s.product_name, s.category_id, k.id sku_id, k.sku_name, k.sale_price
                FROM product_spu s
                JOIN product_sku k ON k.spu_id = s.id AND k.status = 'ENABLED'
                WHERE s.shop_id = ? AND s.sales_status = 'ON_SALE'
                  AND NOT EXISTS (SELECT 1 FROM product_sku child WHERE child.parent_sku_id = k.id)
                ORDER BY s.id, k.id
                """, (rs, row) -> {
            Map<String, Object> leaf = new LinkedHashMap<>();
            leaf.put("spuId", rs.getLong("spu_id"));
            leaf.put("name", rs.getString("product_name"));
            leaf.put("categoryId", rs.getLong("category_id"));
            leaf.put("skuId", rs.getLong("sku_id"));
            leaf.put("spec", rs.getString("sku_name"));
            leaf.put("price", money(rs.getBigDecimal("sale_price")));
            return leaf;
        }, shopId);
        for (Map<String, Object> leaf : leaves) {
            long spuId = ((Number) leaf.get("spuId")).longValue();
            Map<String, Object> product = products.stream().filter(item -> ((Number) item.get("id")).longValue() == spuId).findFirst().orElse(null);
            if (product == null) {
                product = new LinkedHashMap<>();
                product.put("id", spuId);
                product.put("name", leaf.get("name"));
                product.put("categoryId", leaf.get("categoryId"));
                product.put("skus", new ArrayList<Map<String, Object>>());
                products.add(product);
            }
            Map<String, Object> sku = new LinkedHashMap<>();
            sku.put("id", leaf.get("skuId"));
            sku.put("name", leaf.get("spec"));
            sku.put("price", leaf.get("price"));
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> skus = (List<Map<String, Object>>) product.get("skus");
            skus.add(sku);
        }
        body.put("products", products);
        return body;
    }

    @Transactional
    public Map<String, Object> save(long accountId, Long id, ActivityRequest body) {
        long shopId = catalog.merchantShopId(accountId);
        Normalized normalized = normalize(shopId, body);
        if (id == null) {
            GeneratedKeyHolder keys = new GeneratedKeyHolder();
            jdbc.update(connection -> {
                var statement = connection.prepareStatement("""
                        INSERT INTO marketing_activity
                        (shop_id, activity_no, activity_name, activity_type, product_scope_type, product_scope_json,
                         rule_json, start_at, end_at, status, review_remark, created_by)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'DRAFT', ?, ?)
                        """, new String[]{"id"});
                statement.setLong(1, shopId);
                statement.setString(2, nextNo("AC"));
                statement.setString(3, normalized.name());
                statement.setString(4, normalized.type());
                statement.setString(5, normalized.scope());
                statement.setString(6, normalized.scopeJson());
                statement.setString(7, normalized.ruleJson());
                statement.setTimestamp(8, Timestamp.valueOf(normalized.start()));
                statement.setTimestamp(9, Timestamp.valueOf(normalized.end()));
                statement.setString(10, blank(body.note()));
                statement.setLong(11, accountId);
                return statement;
            }, keys);
            id = keys.getKey().longValue();
        } else {
            String status = statusOf(id, shopId);
            if (!"DRAFT".equals(status) && !"REJECTED".equals(status)) {
                throw new ApiException(400, "进行中的活动不能修改");
            }
            jdbc.update("""
                    UPDATE marketing_activity
                    SET activity_name = ?, activity_type = ?, product_scope_type = ?, product_scope_json = ?,
                        rule_json = ?, start_at = ?, end_at = ?, status = 'DRAFT', review_remark = ?
                    WHERE id = ? AND shop_id = ?
                    """, normalized.name(), normalized.type(), normalized.scope(), normalized.scopeJson(),
                    normalized.ruleJson(), Timestamp.valueOf(normalized.start()), Timestamp.valueOf(normalized.end()),
                    blank(body.note()), id, shopId);
            jdbc.update("DELETE FROM marketing_activity_item WHERE activity_id = ?", id);
        }
        for (SeckillLine item : normalized.items()) {
            jdbc.update("""
                    INSERT INTO marketing_activity_item
                    (activity_id, sku_id, activity_price, activity_stock, per_member_limit)
                    VALUES (?, ?, ?, ?, ?)
                    """, id, item.skuId(), item.price(), item.stock(), normalized.limit());
        }
        return detail(id, shopId);
    }

    @Transactional
    public Map<String, Object> submit(long accountId, long id) {
        long shopId = catalog.merchantShopId(accountId);
        Map<String, Object> activity = detail(id, shopId);
        String status = String.valueOf(activity.get("status"));
        if (!"DRAFT".equals(status) && !"REJECTED".equals(status)) {
            throw new ApiException(400, "这个活动不能再次提交");
        }
        String type = String.valueOf(activity.get("type"));
        boolean review = "COUPON".equals(type) || "SECKILL".equals(type);
        jdbc.update("UPDATE marketing_activity SET status = ?, review_remark = NULL WHERE id = ?", review ? "PENDING" : "ACTIVE", id);
        if (!review && "COUPON".equals(type)) {
            ensureTemplate(id);
        }
        return detail(id, shopId);
    }

    @Transactional
    public Map<String, Object> end(long accountId, long id) {
        long shopId = catalog.merchantShopId(accountId);
        String status = statusOf(id, shopId);
        if (!"ACTIVE".equals(status)) {
            throw new ApiException(400, "只有进行中的活动可以结束");
        }
        jdbc.update("UPDATE marketing_activity SET status = 'ENDED' WHERE id = ?", id);
        jdbc.update("""
                UPDATE coupon_template t
                JOIN marketing_activity a ON a.id = ?
                SET t.status = 'ENDED'
                WHERE t.shop_id = a.shop_id AND t.coupon_name = a.activity_name AND t.status = 'ACTIVE'
                """, id);
        return detail(id, shopId);
    }

    public List<Map<String, Object>> platformActivities() {
        return jdbc.query("""
                SELECT a.id, a.activity_no, a.activity_name, a.activity_type, a.product_scope_type, a.rule_json,
                       a.start_at, a.end_at, a.status, a.review_remark, shop.shop_name
                FROM marketing_activity a
                JOIN shop ON shop.id = a.shop_id
                ORDER BY a.id DESC
                """, (rs, row) -> summary(rs));
    }

    public Map<String, Object> platformActivity(long id) {
        List<Long> shops = jdbc.query("SELECT shop_id FROM marketing_activity WHERE id = ?", (rs, row) -> rs.getLong("shop_id"), id);
        if (shops.isEmpty()) {
            throw new ApiException(404, "活动不存在");
        }
        return detail(id, shops.get(0));
    }

    @Transactional
    public Map<String, Object> audit(long reviewerId, long id, boolean pass, String remark) {
        List<Map<String, Object>> found = jdbc.query(
                "SELECT status, activity_type FROM marketing_activity WHERE id = ? FOR UPDATE",
                (rs, row) -> Map.of("status", rs.getString("status"), "type", rs.getString("activity_type")),
                id
        );
        if (found.isEmpty()) {
            throw new ApiException(404, "活动不存在");
        }
        if (!"PENDING".equals(found.get(0).get("status"))) {
            throw new ApiException(400, "这个活动不在待审核");
        }
        if (!pass && (remark == null || remark.isBlank())) {
            throw new ApiException(400, "驳回时请填写原因");
        }
        jdbc.update("""
                UPDATE marketing_activity
                SET status = ?, review_remark = ?, reviewed_by = ?, reviewed_at = NOW()
                WHERE id = ?
                """, pass ? "ACTIVE" : "REJECTED", blank(remark), reviewerId, id);
        if (pass && "COUPON".equals(found.get(0).get("type"))) {
            ensureTemplate(id);
        }
        return platformActivity(id);
    }

    public Deal price(long memberId, long shopId, List<SaleLine> lines, Long memberCouponId, Long templateId) {
        BigDecimal goods = lines.stream().map(line -> line.price().multiply(BigDecimal.valueOf(line.quantity()))).reduce(BigDecimal.ZERO, BigDecimal::add);
        List<Activity> activities = loadActive(shopId);
        Map<Long, BigDecimal> savings = new LinkedHashMap<>();
        Map<Long, Integer> seckillQty = new LinkedHashMap<>();
        Map<Long, Long> seckillSkus = new LinkedHashMap<>();
        boolean blockCoupon = false;
        String activityName = "";
        for (SaleLine line : lines) {
            Offer seckill = seckill(activities, line);
            Offer discount = seckill.savings().signum() > 0 ? Offer.none() : discount(activities, line);
            Offer chosen = seckill.savings().compareTo(discount.savings()) >= 0 ? seckill : discount;
            if (chosen.savings().signum() > 0) {
                savings.put(line.skuId(), chosen.savings());
                activityName = chosen.name();
                if (chosen.activityItemId() != null) {
                    seckillQty.put(chosen.activityItemId(), chosen.units());
                    seckillSkus.put(line.skuId(), chosen.activityItemId());
                }
                if (chosen.blockCoupon()) {
                    blockCoupon = true;
                }
            }
        }
        BigDecimal activity = savings.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        Offer reduction = bestReduction(activities, lines, savings);
        BigDecimal afterReduction = goods.subtract(activity).subtract(reduction.savings()).max(BigDecimal.ZERO);
        List<Map<String, Object>> coupons = coupons(memberId, shopId, activities, lines, savings, afterReduction, blockCoupon);
        BigDecimal coupon = BigDecimal.ZERO;
        String couponName = "";
        Long chosenCoupon = null;
        Long chosenTemplate = null;
        if (!blockCoupon) {
            for (Map<String, Object> item : coupons) {
                boolean picked = (memberCouponId != null && memberCouponId.equals(item.get("memberCouponId")))
                        || (templateId != null && memberCouponId == null && templateId.equals(item.get("templateId")));
                if (picked && Boolean.TRUE.equals(item.get("usable"))) {
                    coupon = new BigDecimal(String.valueOf(item.get("amount")));
                    couponName = String.valueOf(item.get("name"));
                    chosenCoupon = (Long) item.get("memberCouponId");
                    chosenTemplate = (Long) item.get("templateId");
                    item.put("selected", true);
                }
            }
        }
        return new Deal(money(goods), money(activity), money(reduction.savings()), money(coupon), activityName, reduction.name(), couponName, chosenCoupon, chosenTemplate, blockCoupon, seckillQty, seckillSkus, coupons);
    }

    @Transactional
    public void consume(long memberId, long orderId, Deal deal) {
        for (Map.Entry<Long, Integer> entry : deal.seckillQty().entrySet()) {
            int updated = jdbc.update(
                    "UPDATE marketing_activity_item SET activity_stock = activity_stock - ? WHERE id = ? AND activity_stock >= ?",
                    entry.getValue(), entry.getKey(), entry.getValue()
            );
            if (updated == 0) {
                throw new ApiException(400, "秒杀库存不足");
            }
        }
        if (new BigDecimal(deal.coupon()).signum() <= 0) {
            return;
        }
        Long couponId = deal.memberCouponId();
        if (couponId == null && deal.templateId() != null) {
            couponId = claim(memberId, deal.templateId());
        }
        if (couponId == null) {
            throw new ApiException(400, "请重新选择优惠券");
        }
        int updated = jdbc.update("""
                UPDATE member_coupon SET status = 'USED', used_at = NOW()
                WHERE id = ? AND member_id = ? AND status = 'UNUSED'
                """, couponId, memberId);
        if (updated == 0) {
            throw new ApiException(400, "优惠券不可用");
        }
        jdbc.update("""
                INSERT INTO order_coupon (order_id, member_coupon_id, discount_amount) VALUES (?, ?, ?)
                """, orderId, couponId, new BigDecimal(deal.coupon()));
    }

    @Transactional
    public void release(long orderId) {
        List<Map<String, Object>> items = jdbc.query("""
                SELECT quantity, spec_snapshot_json FROM sales_order_item WHERE order_id = ?
                """, (rs, row) -> Map.of("quantity", rs.getInt("quantity"), "spec", rs.getString("spec_snapshot_json")), orderId);
        for (Map<String, Object> item : items) {
            long activityItemId = activityItemId(String.valueOf(item.get("spec")));
            if (activityItemId > 0) {
                jdbc.update(
                        "UPDATE marketing_activity_item SET activity_stock = activity_stock + ? WHERE id = ?",
                        item.get("quantity"), activityItemId
                );
            }
        }
        List<Long> coupons = jdbc.query("SELECT member_coupon_id FROM order_coupon WHERE order_id = ?", (rs, row) -> rs.getLong(1), orderId);
        for (Long couponId : coupons) {
            jdbc.update("UPDATE member_coupon SET status = 'UNUSED', used_at = NULL WHERE id = ? AND status = 'USED'", couponId);
        }
        jdbc.update("DELETE FROM order_coupon WHERE order_id = ?", orderId);
    }

    private long claim(long memberId, long templateId) {
        List<Map<String, Object>> templates = jdbc.query("""
                SELECT total_quantity, received_quantity, per_member_limit, valid_end_at, status
                FROM coupon_template WHERE id = ? FOR UPDATE
                """, (rs, row) -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("total", rs.getObject("total_quantity") == null ? null : rs.getInt("total_quantity"));
            item.put("received", rs.getInt("received_quantity"));
            item.put("limit", rs.getInt("per_member_limit"));
            item.put("end", rs.getTimestamp("valid_end_at"));
            item.put("status", rs.getString("status"));
            return item;
        }, templateId);
        if (templates.isEmpty() || !"ACTIVE".equals(templates.get(0).get("status"))) {
            throw new ApiException(400, "优惠券已失效");
        }
        Map<String, Object> template = templates.get(0);
        Integer total = (Integer) template.get("total");
        int received = (Integer) template.get("received");
        if (total != null && received >= total) {
            throw new ApiException(400, "优惠券已领完");
        }
        Integer owned = jdbc.queryForObject(
                "SELECT COUNT(*) FROM member_coupon WHERE coupon_template_id = ? AND member_id = ?",
                Integer.class, templateId, memberId
        );
        if (owned != null && owned >= (Integer) template.get("limit")) {
            throw new ApiException(400, "已达到每人限领张数");
        }
        GeneratedKeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            var statement = connection.prepareStatement("""
                    INSERT INTO member_coupon (coupon_template_id, member_id, coupon_code, status, expires_at)
                    VALUES (?, ?, ?, 'UNUSED', ?)
                    """, new String[]{"id"});
            statement.setLong(1, templateId);
            statement.setLong(2, memberId);
            statement.setString(3, nextNo("CP"));
            statement.setTimestamp(4, (Timestamp) template.get("end"));
            return statement;
        }, keys);
        jdbc.update("UPDATE coupon_template SET received_quantity = received_quantity + 1 WHERE id = ?", templateId);
        return keys.getKey().longValue();
    }

    private void ensureTemplate(long activityId) {
        List<Map<String, Object>> rows = jdbc.query("""
                SELECT shop_id, activity_name, product_scope_type, product_scope_json, rule_json, start_at, end_at, created_by
                FROM marketing_activity WHERE id = ?
                """, (rs, row) -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("shopId", rs.getLong("shop_id"));
            item.put("name", rs.getString("activity_name"));
            item.put("scope", rs.getString("product_scope_type"));
            item.put("scopeJson", rs.getString("product_scope_json"));
            item.put("rule", rs.getString("rule_json"));
            item.put("start", rs.getTimestamp("start_at"));
            item.put("end", rs.getTimestamp("end_at"));
            item.put("creator", rs.getLong("created_by"));
            return item;
        }, activityId);
        if (rows.isEmpty()) {
            return;
        }
        JsonNode rule = read(String.valueOf(rows.get(0).get("rule")));
        if (rule.path("templateId").asLong(0) > 0) {
            jdbc.update("UPDATE coupon_template SET status = 'ACTIVE' WHERE id = ?", rule.path("templateId").asLong());
            return;
        }
        GeneratedKeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            var statement = connection.prepareStatement("""
                    INSERT INTO coupon_template
                    (shop_id, coupon_name, coupon_type, amount, threshold_amount, scope_type, scope_json,
                     total_quantity, per_member_limit, valid_start_at, valid_end_at, status, created_by)
                    VALUES (?, ?, 'CASH', ?, ?, ?, ?, ?, ?, ?, ?, 'ACTIVE', ?)
                    """, new String[]{"id"});
            statement.setLong(1, (Long) rows.get(0).get("shopId"));
            statement.setString(2, String.valueOf(rows.get(0).get("name")));
            statement.setBigDecimal(3, decimal(rule, "amount"));
            statement.setBigDecimal(4, decimal(rule, "min"));
            statement.setString(5, scopeType(String.valueOf(rows.get(0).get("scope"))));
            statement.setString(6, String.valueOf(rows.get(0).get("scopeJson")));
            statement.setInt(7, rule.path("qty").asInt());
            statement.setInt(8, rule.path("limit").asInt(1));
            statement.setTimestamp(9, (Timestamp) rows.get(0).get("start"));
            statement.setTimestamp(10, (Timestamp) rows.get(0).get("end"));
            statement.setLong(11, (Long) rows.get(0).get("creator"));
            return statement;
        }, keys);
        ObjectNode next = (ObjectNode) rule;
        next.put("templateId", keys.getKey().longValue());
        jdbc.update("UPDATE marketing_activity SET rule_json = ? WHERE id = ?", next.toString(), activityId);
    }

    private List<Activity> loadActive(long shopId) {
        LocalDateTime now = LocalDateTime.now();
        List<Activity> activities = jdbc.query("""
                SELECT id, activity_name, activity_type, product_scope_type, product_scope_json, rule_json, start_at, end_at
                FROM marketing_activity
                WHERE shop_id = ? AND status = 'ACTIVE'
                """, (rs, row) -> {
            LocalDateTime start = rs.getTimestamp("start_at").toLocalDateTime();
            LocalDateTime end = rs.getTimestamp("end_at").toLocalDateTime();
            if (start.isAfter(now) || end.isBefore(now)) {
                return null;
            }
            return new Activity(
                rs.getLong("id"),
                rs.getString("activity_name"),
                rs.getString("activity_type"),
                rs.getString("product_scope_type"),
                read(rs.getString("product_scope_json")),
                read(rs.getString("rule_json")),
                new ArrayList<>()
            );
        }, shopId);
        activities = activities.stream().filter(item -> item != null).toList();
        if (activities.isEmpty()) {
            return activities;
        }
        String marks = String.join(",", activities.stream().map(item -> "?").toList());
        Object[] ids = activities.stream().map(Activity::id).toArray();
        List<Map<String, Object>> offers = jdbc.query("""
                SELECT id, activity_id, sku_id, activity_price, activity_stock, per_member_limit
                FROM marketing_activity_item WHERE activity_id IN (""" + marks + ")",
                (rs, row) -> {
                    Map<String, Object> offer = new LinkedHashMap<>();
                    offer.put("activityId", rs.getLong("activity_id"));
                    offer.put("item", new SkuOffer(
                            rs.getLong("id"),
                            rs.getLong("sku_id"),
                            rs.getBigDecimal("activity_price"),
                            rs.getObject("activity_stock") == null ? 0 : rs.getInt("activity_stock"),
                            rs.getObject("per_member_limit") == null ? 0 : rs.getInt("per_member_limit")
                    ));
                    return offer;
                }, ids);
        for (Map<String, Object> offer : offers) {
            long activityId = (Long) offer.get("activityId");
            activities.stream().filter(item -> item.id() == activityId).findFirst()
                    .ifPresent(item -> item.items().add((SkuOffer) offer.get("item")));
        }
        return activities;
    }

    private Offer seckill(List<Activity> activities, SaleLine line) {
        Offer best = Offer.none();
        for (Activity activity : activities) {
            if (!"SECKILL".equals(activity.type())) {
                continue;
            }
            for (SkuOffer item : activity.items()) {
                if (item.skuId() != line.skuId() || item.stock() < line.quantity()) {
                    continue;
                }
                int limit = item.limit() > 0 ? item.limit() : activity.rule().path("limit").asInt(line.quantity());
                if (line.quantity() > limit || item.price() == null || item.price().compareTo(line.price()) >= 0) {
                    continue;
                }
                BigDecimal savings = line.price().subtract(item.price()).multiply(BigDecimal.valueOf(line.quantity()));
                if (savings.compareTo(best.savings()) > 0) {
                    best = new Offer(savings, activity.name(), item.id(), line.quantity(), false);
                }
            }
        }
        return best;
    }

    private Offer discount(List<Activity> activities, SaleLine line) {
        Offer best = Offer.none();
        for (Activity activity : activities) {
            if (!"DISCOUNT".equals(activity.type()) || !covers(activity, line)) {
                continue;
            }
            JsonNode rule = activity.rule();
            int limit = Math.max(rule.path("limit").asInt(1), 1);
            int units = Math.min(line.quantity(), limit);
            BigDecimal rate = null;
            if ("多件折扣".equals(rule.path("mode").asText())) {
                for (JsonNode tier : rule.path("tiers")) {
                    if (line.quantity() >= tier.path("qty").asInt() && tier.path("rate").asDouble() > 0) {
                        rate = BigDecimal.valueOf(tier.path("rate").asDouble());
                    }
                }
            } else if (rule.path("rate").asDouble() > 0) {
                rate = BigDecimal.valueOf(rule.path("rate").asDouble());
            }
            if (rate == null) {
                continue;
            }
            BigDecimal payRate = rate.divide(BigDecimal.TEN, 4, RoundingMode.HALF_UP);
            BigDecimal savings = line.price().multiply(BigDecimal.valueOf(units)).multiply(BigDecimal.ONE.subtract(payRate));
            savings = savings.setScale(2, RoundingMode.HALF_UP);
            if (savings.compareTo(best.savings()) > 0) {
                best = new Offer(savings, activity.name(), null, units, !"可与优惠券同享".equals(rule.path("stack").asText("不可与优惠券同享")));
            }
        }
        return best;
    }

    private Offer bestReduction(List<Activity> activities, List<SaleLine> lines, Map<Long, BigDecimal> savings) {
        Offer best = Offer.none();
        for (Activity activity : activities) {
            if (!"FULL_REDUCTION".equals(activity.type())) {
                continue;
            }
            BigDecimal base = BigDecimal.ZERO;
            for (SaleLine line : lines) {
                if (covers(activity, line)) {
                    base = base.add(line.price().multiply(BigDecimal.valueOf(line.quantity())).subtract(savings.getOrDefault(line.skuId(), BigDecimal.ZERO)));
                }
            }
            JsonNode rule = activity.rule();
            BigDecimal threshold = decimal(rule, "threshold");
            BigDecimal cut = decimal(rule, "cut");
            if (threshold.signum() <= 0 || base.compareTo(threshold) < 0) {
                continue;
            }
            BigDecimal times = "每满一次减".equals(rule.path("mode").asText())
                    ? new BigDecimal(base.divide(threshold, 0, RoundingMode.DOWN).toPlainString())
                    : BigDecimal.ONE;
            BigDecimal amount = cut.multiply(times).min(base).setScale(2, RoundingMode.HALF_UP);
            if (amount.compareTo(best.savings()) > 0) {
                best = new Offer(amount, activity.name(), null, 0, false);
            }
        }
        return best;
    }

    private List<Map<String, Object>> coupons(long memberId, long shopId, List<Activity> activities, List<SaleLine> lines, Map<Long, BigDecimal> savings, BigDecimal payable, boolean blocked) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Activity activity : activities) {
            if (!"COUPON".equals(activity.type())) {
                continue;
            }
            long templateId = activity.rule().path("templateId").asLong(0);
            if (templateId <= 0) {
                continue;
            }
            List<Long> owned = jdbc.query("""
                    SELECT id FROM member_coupon
                    WHERE coupon_template_id = ? AND member_id = ? AND status = 'UNUSED' AND expires_at >= ?
                    ORDER BY id LIMIT 1
                    """, (rs, row) -> rs.getLong("id"), templateId, memberId, Timestamp.valueOf(LocalDateTime.now()));
            boolean canClaim = owned.isEmpty() && claimable(templateId, memberId);
            if (owned.isEmpty() && !canClaim) {
                continue;
            }
            BigDecimal base = couponBase(activity, lines, savings, payable);
            BigDecimal threshold = decimal(activity.rule(), "min");
            BigDecimal amount = decimal(activity.rule(), "amount").min(base.max(BigDecimal.ZERO));
            boolean usable = !blocked && base.compareTo(threshold) >= 0 && amount.signum() > 0;
            String reason = blocked ? "当前折扣不可与优惠券同享" : (base.compareTo(threshold) < 0 ? "未满使用门槛" : "");
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("memberCouponId", owned.isEmpty() ? null : owned.get(0));
            item.put("templateId", templateId);
            item.put("name", activity.name());
            item.put("amount", money(amount));
            item.put("threshold", money(threshold));
            item.put("owned", !owned.isEmpty());
            item.put("usable", usable);
            item.put("reason", usable ? "" : reason);
            item.put("selected", false);
            result.add(item);
        }
        result.sort(Comparator.comparing((Map<String, Object> item) -> Boolean.TRUE.equals(item.get("usable"))).reversed());
        return result;
    }

    private BigDecimal couponBase(Activity activity, List<SaleLine> lines, Map<Long, BigDecimal> savings, BigDecimal payable) {
        if ("ALL".equals(activity.scope())) {
            return payable;
        }
        BigDecimal base = BigDecimal.ZERO;
        for (SaleLine line : lines) {
            if (covers(activity, line)) {
                base = base.add(line.price().multiply(BigDecimal.valueOf(line.quantity())).subtract(savings.getOrDefault(line.skuId(), BigDecimal.ZERO)));
            }
        }
        return base.min(payable);
    }

    private boolean claimable(long templateId, long memberId) {
        Timestamp now = Timestamp.valueOf(LocalDateTime.now());
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM coupon_template t
                WHERE t.id = ? AND t.status = 'ACTIVE' AND t.valid_start_at <= ? AND t.valid_end_at >= ?
                  AND (t.total_quantity IS NULL OR t.received_quantity < t.total_quantity)
                  AND (SELECT COUNT(*) FROM member_coupon c WHERE c.coupon_template_id = t.id AND c.member_id = ?) < t.per_member_limit
                """, Integer.class, templateId, now, now, memberId);
        return count != null && count > 0;
    }

    private boolean covers(Activity activity, SaleLine line) {
        if ("ALL".equals(activity.scope())) {
            return true;
        }
        long target = "CATEGORY".equals(activity.scope()) ? line.categoryId() : line.spuId();
        for (JsonNode id : activity.scopeJson().path("ids")) {
            if (id.asLong() == target) {
                return true;
            }
        }
        return false;
    }

    private Map<String, Object> detail(long id, long shopId) {
        List<Map<String, Object>> found = jdbc.query("""
                SELECT a.id, a.activity_no, a.activity_name, a.activity_type, a.product_scope_type, a.product_scope_json,
                       a.rule_json, a.start_at, a.end_at, a.status, a.review_remark, shop.shop_name
                FROM marketing_activity a JOIN shop ON shop.id = a.shop_id
                WHERE a.id = ? AND a.shop_id = ?
                """, (rs, row) -> {
            Map<String, Object> item = summary(rs);
            item.put("scopeIds", ids(read(rs.getString("product_scope_json"))));
            item.put("rule", read(rs.getString("rule_json")));
            item.put("note", rs.getString("review_remark"));
            return item;
        }, id, shopId);
        if (found.isEmpty()) {
            throw new ApiException(404, "活动不存在");
        }
        Map<String, Object> activity = found.get(0);
        activity.put("items", jdbc.query("""
                SELECT i.sku_id, i.activity_price, i.activity_stock, k.sku_name, k.sale_price, s.product_name
                FROM marketing_activity_item i
                JOIN product_sku k ON k.id = i.sku_id
                JOIN product_spu s ON s.id = k.spu_id
                WHERE i.activity_id = ?
                """, (rs, row) -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("skuId", rs.getLong("sku_id"));
            item.put("name", rs.getString("product_name"));
            item.put("spec", rs.getString("sku_name"));
            item.put("salePrice", money(rs.getBigDecimal("sale_price")));
            item.put("price", money(rs.getBigDecimal("activity_price")));
            item.put("stock", rs.getInt("activity_stock"));
            return item;
        }, id));
        return activity;
    }

    private Map<String, Object> summary(ResultSet rs) throws SQLException {
        String type = rs.getString("activity_type");
        String status = rs.getString("status");
        JsonNode rule = read(rs.getString("rule_json"));
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", rs.getLong("id"));
        item.put("activityNo", rs.getString("activity_no"));
        item.put("name", rs.getString("activity_name"));
        item.put("type", type);
        item.put("typeName", typeName(type));
        item.put("scope", scopeName(rs.getString("product_scope_type")));
        item.put("summary", ruleSummary(type, rule));
        item.put("start", clock(rs.getTimestamp("start_at")));
        item.put("end", clock(rs.getTimestamp("end_at")));
        item.put("status", status);
        item.put("statusName", statusName(status));
        item.put("reviewRemark", rs.getString("review_remark"));
        item.put("shopName", rs.getString("shop_name"));
        item.put("needsReview", "COUPON".equals(type) || "SECKILL".equals(type));
        return item;
    }

    private Normalized normalize(long shopId, ActivityRequest body) {
        String name = body.name() == null ? "" : body.name().trim();
        if (name.isBlank()) {
            throw new ApiException(400, "请填写活动名称");
        }
        String type = typeCode(body.type());
        LocalDateTime start = parseTime(body.start(), false);
        LocalDateTime end = parseTime(body.end(), true);
        if (!end.isAfter(start)) {
            throw new ApiException(400, "请填写有效的活动时间");
        }
        boolean fixedProduct = "DISCOUNT".equals(type) || "SECKILL".equals(type);
        String scope = fixedProduct ? "PRODUCT" : scopeCode(body.scope());
        List<Long> scopeIds = body.scopeIds() == null ? List.of() : body.scopeIds();
        if (!"SECKILL".equals(type) && !"ALL".equals(scope) && scopeIds.isEmpty()) {
            throw new ApiException(400, "CATEGORY".equals(scope) ? "请至少选择一个类目" : "请至少选择一个商品");
        }
        JsonNode rule = body.rule() == null ? json.createObjectNode() : body.rule();
        List<SeckillLine> items = List.of();
        int limit = rule.path("limit").asInt(0);
        if ("FULL_REDUCTION".equals(type)) {
            BigDecimal threshold = decimal(rule, "threshold");
            BigDecimal cut = decimal(rule, "cut");
            if (threshold.signum() <= 0 || cut.signum() <= 0 || cut.compareTo(threshold) >= 0) {
                throw new ApiException(400, "减免金额必须大于 0，且小于门槛金额");
            }
        } else if ("COUPON".equals(type)) {
            BigDecimal amount = decimal(rule, "amount");
            BigDecimal min = decimal(rule, "min");
            int qty = rule.path("qty").asInt(0);
            limit = rule.path("limit").asInt(0);
            if (amount.signum() <= 0 || min.signum() < 0 || (min.signum() > 0 && min.compareTo(amount) <= 0) || qty <= 0 || limit <= 0) {
                throw new ApiException(400, "优惠券需填写面额、使用门槛、发放数量和限领张数。有门槛时，门槛必须高于面额");
            }
        } else if ("DISCOUNT".equals(type)) {
            limit = rule.path("limit").asInt(0);
            if (limit <= 0) {
                throw new ApiException(400, "请设置每人限购件数");
            }
            if ("多件折扣".equals(rule.path("mode").asText())) {
                if (!rule.path("tiers").isArray() || rule.path("tiers").size() < 1 || rule.path("tiers").size() > 5) {
                    throw new ApiException(400, "多件折扣请填写 1 到 5 档");
                }
                int previousQty = 1;
                double previousRate = 10;
                int maxQty = 0;
                for (JsonNode tier : rule.path("tiers")) {
                    int qty = tier.path("qty").asInt();
                    double rate = tier.path("rate").asDouble();
                    if (qty < 2 || qty <= previousQty || rate < 1 || rate > 9.9 || rate >= previousRate) {
                        throw new ApiException(400, "多件折扣的件数须从 2 起递增，折扣填写 1 到 9.9，且件数越多折扣越低");
                    }
                    previousQty = qty;
                    previousRate = rate;
                    maxQty = qty;
                }
                if (maxQty > limit) {
                    throw new ApiException(400, "每人限购不能小于最高档件数");
                }
            } else {
                double rate = rule.path("rate").asDouble();
                if (rate < 1 || rate > 9.9) {
                    throw new ApiException(400, "折扣请填写 1 到 9.9");
                }
            }
        } else {
            limit = rule.path("limit").asInt(0);
            if (rule.path("slot").asText("").isBlank() || limit <= 0) {
                throw new ApiException(400, "请选择秒杀场次和限购件数");
            }
            items = validSeckill(shopId, body.items());
            scopeIds = items.stream().map(SeckillLine::spuId).distinct().toList();
        }
        return new Normalized(name, type, scope, writeIds(scopeIds), rule.toString(), start, end, items, limit);
    }

    private List<SeckillLine> validSeckill(long shopId, List<SeckillLine> items) {
        if (items == null || items.isEmpty()) {
            throw new ApiException(400, "请勾选商品并填写秒杀价、秒杀库存");
        }
        List<SeckillLine> ready = new ArrayList<>();
        for (SeckillLine item : items) {
            if (item.skuId() == null || item.price() == null || item.stock() == null) {
                continue;
            }
            List<Map<String, Object>> skus = jdbc.query("""
                    SELECT k.sale_price, s.id spu_id FROM product_sku k
                    JOIN product_spu s ON s.id = k.spu_id
                    WHERE k.id = ? AND s.shop_id = ? AND k.status = 'ENABLED' AND s.sales_status = 'ON_SALE'
                    """, (rs, row) -> Map.of("price", rs.getBigDecimal("sale_price"), "spuId", rs.getLong("spu_id")), item.skuId(), shopId);
            if (skus.isEmpty()) {
                throw new ApiException(400, "秒杀商品不存在");
            }
            BigDecimal sale = (BigDecimal) skus.get(0).get("price");
            if (item.price().signum() <= 0 || item.price().compareTo(sale) >= 0 || item.stock() < 0) {
                throw new ApiException(400, "秒杀价必须大于 0 且低于售价，库存需为非负整数");
            }
            ready.add(new SeckillLine(item.skuId(), (Long) skus.get(0).get("spuId"), item.price(), item.stock()));
        }
        if (ready.isEmpty()) {
            throw new ApiException(400, "请勾选商品并填写秒杀价、秒杀库存");
        }
        return ready;
    }

    private String statusOf(long id, long shopId) {
        List<String> found = jdbc.query(
                "SELECT status FROM marketing_activity WHERE id = ? AND shop_id = ?",
                (rs, row) -> rs.getString("status"), id, shopId
        );
        if (found.isEmpty()) {
            throw new ApiException(404, "活动不存在");
        }
        return found.get(0);
    }

    private static String typeCode(String name) {
        return switch (name == null ? "" : name) {
            case "满减", "FULL_REDUCTION" -> "FULL_REDUCTION";
            case "优惠券", "COUPON" -> "COUPON";
            case "折扣", "DISCOUNT" -> "DISCOUNT";
            case "秒杀", "SECKILL" -> "SECKILL";
            default -> throw new ApiException(400, "活动只支持满减、优惠券、折扣、秒杀");
        };
    }

    private static String typeName(String code) {
        return switch (code) {
            case "FULL_REDUCTION" -> "满减";
            case "COUPON" -> "优惠券";
            case "DISCOUNT" -> "折扣";
            case "SECKILL" -> "秒杀";
            default -> code;
        };
    }

    private static String scopeCode(String name) {
        return switch (name == null ? "" : name) {
            case "全店", "ALL" -> "ALL";
            case "指定类目", "CATEGORY" -> "CATEGORY";
            case "指定商品", "PRODUCT" -> "PRODUCT";
            default -> throw new ApiException(400, "请选择适用范围");
        };
    }

    private static String scopeName(String code) {
        return switch (code) {
            case "ALL" -> "全店";
            case "CATEGORY" -> "指定类目";
            case "PRODUCT" -> "指定商品";
            default -> code;
        };
    }

    private static String scopeType(String code) {
        return switch (code) {
            case "CATEGORY" -> "CATEGORY";
            case "PRODUCT" -> "PRODUCT";
            default -> "GENERAL";
        };
    }

    private static String statusName(String status) {
        return switch (status) {
            case "DRAFT" -> "草稿";
            case "PENDING" -> "待审核";
            case "ACTIVE" -> "进行中";
            case "REJECTED" -> "已驳回";
            case "ENDED" -> "已结束";
            default -> status;
        };
    }

    private static String ruleSummary(String type, JsonNode rule) {
        if ("FULL_REDUCTION".equals(type)) {
            return ("每满一次减".equals(rule.path("mode").asText()) ? "每满 " : "满 ") + rule.path("threshold").asText() + " 减 " + rule.path("cut").asText();
        }
        if ("COUPON".equals(type)) {
            return "满 " + rule.path("min").asText("0") + " 减 " + rule.path("amount").asText() + " · 发放 " + rule.path("qty").asText("0") + " 张";
        }
        if ("DISCOUNT".equals(type) && "多件折扣".equals(rule.path("mode").asText())) {
            List<String> tiers = new ArrayList<>();
            for (JsonNode tier : rule.path("tiers")) {
                tiers.add("满 " + tier.path("qty").asText() + " 件 " + tier.path("rate").asText() + " 折");
            }
            return String.join("、", tiers);
        }
        if ("DISCOUNT".equals(type)) {
            return rule.path("rate").asText() + " 折 · 限购 " + rule.path("limit").asText("1") + " 件";
        }
        if ("SECKILL".equals(type)) {
            return rule.path("slot").asText() + " · 限购 " + rule.path("limit").asText("1") + " 件";
        }
        return "";
    }

    private String writeIds(List<Long> ids) {
        try {
            return json.writeValueAsString(Map.of("ids", ids));
        } catch (Exception exception) {
            throw new ApiException(400, "适用范围无法保存");
        }
    }

    private List<Long> ids(JsonNode node) {
        List<Long> ids = new ArrayList<>();
        for (JsonNode id : node.path("ids")) {
            ids.add(id.asLong());
        }
        return ids;
    }

    private JsonNode read(String raw) {
        try {
            return raw == null || raw.isBlank() ? json.createObjectNode() : json.readTree(raw);
        } catch (Exception exception) {
            return json.createObjectNode();
        }
    }

    private static BigDecimal decimal(JsonNode node, String field) {
        if (!node.has(field) || node.get(field).isNull()) {
            return BigDecimal.ZERO;
        }
        return new BigDecimal(node.get(field).asText("0")).setScale(2, RoundingMode.HALF_UP);
    }

    private static long activityItemId(String raw) {
        try {
            int mark = raw.indexOf("\"activityItemId\"");
            if (mark < 0) {
                return 0;
            }
            String digits = raw.substring(mark).replaceAll("\\D+", " ").trim().split(" ")[0];
            return Long.parseLong(digits);
        } catch (Exception exception) {
            return 0;
        }
    }

    private static LocalDateTime parseTime(String raw, boolean end) {
        if (raw == null || raw.isBlank()) {
            throw new ApiException(400, "请填写活动时间");
        }
        String text = raw.trim().replace('T', ' ');
        if (text.length() == 10) {
            text = text + (end ? " 23:59:59" : " 00:00:00");
        } else if (text.length() == 16) {
            text = text + ":00";
        }
        return LocalDateTime.parse(text, CLOCK);
    }

    private static String clock(Timestamp value) {
        return value == null ? "" : value.toLocalDateTime().format(CLOCK);
    }

    private static String money(BigDecimal value) {
        return (value == null ? BigDecimal.ZERO : value).setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    private static String blank(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String nextNo(String prefix) {
        return prefix + DateTimeFormatter.ofPattern("yyyyMMddHHmmss").format(LocalDateTime.now())
                + ThreadLocalRandom.current().nextInt(1000, 10000);
    }

    public record SaleLine(long skuId, long spuId, long categoryId, BigDecimal price, int quantity) {
    }

    public record Deal(
            String goods,
            String activity,
            String fullReduction,
            String coupon,
            String activityName,
            String fullReductionName,
            String couponName,
            Long memberCouponId,
            Long templateId,
            boolean blockCoupon,
            Map<Long, Integer> seckillQty,
            Map<Long, Long> seckillSkus,
            List<Map<String, Object>> coupons
    ) {
    }

    public record ActivityRequest(
            String name,
            String type,
            String scope,
            List<Long> scopeIds,
            String start,
            String end,
            String note,
            JsonNode rule,
            List<SeckillLine> items
    ) {
    }

    public record SeckillLine(Long skuId, Long spuId, BigDecimal price, Integer stock) {
        public SeckillLine(Long skuId, BigDecimal price, Integer stock) {
            this(skuId, null, price, stock);
        }
    }

    public record AuditRequest(Boolean pass, String remark) {
    }

    private record Normalized(
            String name,
            String type,
            String scope,
            String scopeJson,
            String ruleJson,
            LocalDateTime start,
            LocalDateTime end,
            List<SeckillLine> items,
            int limit
    ) {
    }

    private record Activity(long id, String name, String type, String scope, JsonNode scopeJson, JsonNode rule, List<SkuOffer> items) {
    }

    private record SkuOffer(long id, long skuId, BigDecimal price, int stock, int limit) {
    }

    private record Offer(BigDecimal savings, String name, Long activityItemId, int units, boolean blockCoupon) {
        private static Offer none() {
            return new Offer(BigDecimal.ZERO, "", null, 0, false);
        }
    }
}
