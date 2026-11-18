package com.emall.trade;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

import com.emall.api.ApiException;
import com.emall.catalog.CatalogService;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class SettlementService {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter DAY = DateTimeFormatter.ISO_LOCAL_DATE;
    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final BigDecimal HUNDRED = new BigDecimal("100");

    private final JdbcTemplate jdbc;
    private final CatalogService catalog;
    private final TransactionTemplate transactions;

    public SettlementService(JdbcTemplate jdbc, CatalogService catalog, PlatformTransactionManager manager) {
        this.jdbc = jdbc;
        this.catalog = catalog;
        this.transactions = new TransactionTemplate(manager);
    }

    public Map<String, Object> generatePrevious() {
        List<Map<String, Object>> created = new ArrayList<>();
        List<Map<String, Object>> skipped = new ArrayList<>();
        for (Shop shop : shops()) {
            Period period = previousPeriod(shop.cycle(), LocalDate.now(ZONE));
            try {
                Map<String, Object> bill = transactions.execute(status -> create(shop, period));
                if (bill == null) {
                    skipped.add(skip(shop, "这个周期已经出过账"));
                } else {
                    created.add(bill);
                }
            } catch (DuplicateKeyException exception) {
                skipped.add(skip(shop, "这个周期已经出过账"));
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("created", created);
        result.put("skipped", skipped);
        return result;
    }

    public List<Map<String, Object>> platformCurrent() {
        LocalDate today = LocalDate.now(ZONE);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Shop shop : shops()) {
            rows.add(preview(shop, currentPeriod(shop.cycle(), today)));
        }
        return rows;
    }

    public Map<String, Object> merchantCurrent(long accountId) {
        long shopId = catalog.merchantShopId(accountId);
        Shop shop = shops().stream().filter(item -> item.id() == shopId).findFirst()
                .orElseThrow(() -> new ApiException(404, "店铺不存在"));
        return preview(shop, currentPeriod(shop.cycle(), LocalDate.now(ZONE)));
    }

    public List<Map<String, Object>> platformList() {
        return bills("1 = 1");
    }

    public Map<String, Object> platformDetail(long id) {
        return detail(id, "1 = 1");
    }

    public Map<String, Object> reconcile(long accountId, long id, BigDecimal adjustment) {
        Bill bill = lock(id);
        if (!"PENDING_RECONCILE".equals(bill.status())) {
            throw new ApiException(400, "这张结算单已经核对过");
        }
        BigDecimal adjust = adjustment == null ? BigDecimal.ZERO : adjustment.setScale(2, RoundingMode.HALF_UP);
        if (adjust.abs().compareTo(new BigDecimal("100000")) > 0) {
            throw new ApiException(400, "调整金额过大");
        }
        BigDecimal payable = payable(bill.orderAmount(), bill.refundAmount(), bill.commissionAmount(), adjust);
        jdbc.update("""
                UPDATE merchant_settlement
                SET adjustment_amount = ?, payable_amount = ?, status = 'RECONCILED',
                    reconciled_by = ?, reconciled_at = NOW()
                WHERE id = ?
                """, adjust, payable, accountId, id);
        return detail(id, "1 = 1");
    }

    public Map<String, Object> pay(long id) {
        Bill bill = lock(id);
        if (!"RECONCILED".equals(bill.status())) {
            throw new ApiException(400, "请先核对，再标记打款");
        }
        jdbc.update("""
                UPDATE merchant_settlement SET status = 'PAID', paid_at = NOW() WHERE id = ?
                """, id);
        return detail(id, "1 = 1");
    }

    public List<Map<String, Object>> merchantList(long accountId) {
        long shopId = catalog.merchantShopId(accountId);
        return bills("s.shop_id = ?", shopId);
    }

    public Map<String, Object> merchantDetail(long accountId, long id) {
        long shopId = catalog.merchantShopId(accountId);
        return detail(id, "s.shop_id = ?", shopId);
    }

    private Map<String, Object> create(Shop shop, Period period) {
        Integer exists = jdbc.queryForObject("""
                SELECT COUNT(*) FROM merchant_settlement
                WHERE shop_id = ? AND period_start = ? AND period_end = ?
                """, Integer.class, shop.id(), Date.valueOf(period.start()), Date.valueOf(period.end()));
        if (exists != null && exists > 0) {
            return null;
        }
        Totals totals = totals(shop, period);
        BigDecimal commission = commission(totals.net(), shop.rate());
        BigDecimal payable = payable(totals.orderAmount(), totals.refundAmount(), commission, BigDecimal.ZERO);
        long id = insert(shop, period, totals, commission, payable);
        return brief(id);
    }

    private long insert(Shop shop, Period period, Totals totals, BigDecimal commission, BigDecimal payable) {
        for (int attempt = 0; attempt < 5; attempt++) {
            String no = nextNo();
            try {
                GeneratedKeyHolder keys = new GeneratedKeyHolder();
                jdbc.update(connection -> {
                    var statement = connection.prepareStatement("""
                            INSERT INTO merchant_settlement
                            (settlement_no, merchant_id, shop_id, period_start, period_end,
                             order_amount, refund_amount, commission_amount, adjustment_amount, payable_amount, status)
                            VALUES (?, ?, ?, ?, ?, ?, ?, ?, 0, ?, 'PENDING_RECONCILE')
                            """, new String[]{"id"});
                    statement.setString(1, no);
                    statement.setLong(2, shop.merchantId());
                    statement.setLong(3, shop.id());
                    statement.setDate(4, Date.valueOf(period.start()));
                    statement.setDate(5, Date.valueOf(period.end()));
                    statement.setBigDecimal(6, totals.orderAmount());
                    statement.setBigDecimal(7, totals.refundAmount());
                    statement.setBigDecimal(8, commission);
                    statement.setBigDecimal(9, payable);
                    return statement;
                }, keys);
                return keys.getKey().longValue();
            } catch (DuplicateKeyException exception) {
                Integer samePeriod = jdbc.queryForObject("""
                        SELECT COUNT(*) FROM merchant_settlement
                        WHERE shop_id = ? AND period_start = ? AND period_end = ?
                        """, Integer.class, shop.id(), Date.valueOf(period.start()), Date.valueOf(period.end()));
                if (samePeriod != null && samePeriod > 0) {
                    throw exception;
                }
            }
        }
        throw new ApiException(500, "结算单号生成失败，请重试");
    }

    private Totals totals(Shop shop, Period period) {
        Timestamp from = Timestamp.valueOf(period.start().atStartOfDay());
        Timestamp to = Timestamp.valueOf(period.end().plusDays(1).atStartOfDay());
        BigDecimal orders = jdbc.queryForObject("""
                SELECT COALESCE(SUM(paid_amount), 0) FROM sales_order
                WHERE shop_id = ? AND completed_at >= ? AND completed_at < ?
                """, BigDecimal.class, shop.id(), from, to);
        BigDecimal refunds = jdbc.queryForObject("""
                SELECT COALESCE(SUM(p.amount), 0)
                FROM payment_record p
                JOIN after_sale a ON a.after_sale_no = p.related_no
                JOIN sales_order o ON o.id = a.order_id
                WHERE p.trade_type = 'REFUND' AND p.status = 'SUCCESS'
                  AND o.shop_id = ? AND o.completed_at IS NOT NULL
                  AND (
                    (p.paid_at <= o.completed_at AND o.completed_at >= ? AND o.completed_at < ?)
                    OR (p.paid_at > o.completed_at AND p.paid_at >= ? AND p.paid_at < ?)
                  )
                """, BigDecimal.class, shop.id(), from, to, from, to);
        BigDecimal orderAmount = scale(orders);
        BigDecimal refundAmount = scale(refunds);
        return new Totals(orderAmount, refundAmount, orderAmount.subtract(refundAmount));
    }

    private List<Map<String, Object>> bills(String where, Object... args) {
        return jdbc.query("""
                SELECT s.id, s.settlement_no, s.period_start, s.period_end, s.order_amount, s.refund_amount,
                       s.commission_amount, s.adjustment_amount, s.payable_amount, s.status, s.paid_at,
                       h.shop_name, h.commission_rate
                FROM merchant_settlement s
                JOIN shop h ON h.id = s.shop_id
                WHERE %s
                ORDER BY s.id DESC
                """.formatted(where), (rs, row) -> {
            Map<String, Object> item = moneyRow(rs);
            item.put("id", rs.getLong("id"));
            item.put("no", rs.getString("settlement_no"));
            item.put("shopName", rs.getString("shop_name"));
            item.put("rate", percent(rs.getBigDecimal("commission_rate")));
            item.put("period", rs.getDate("period_start").toLocalDate() + " ~ " + rs.getDate("period_end").toLocalDate());
            item.put("periodStart", rs.getDate("period_start").toLocalDate().format(DAY));
            item.put("periodEnd", rs.getDate("period_end").toLocalDate().format(DAY));
            item.put("status", rs.getString("status"));
            item.put("statusName", statusName(rs.getString("status")));
            item.put("paidAt", clock(rs.getTimestamp("paid_at")));
            return item;
        }, args);
    }

    private Map<String, Object> detail(long id, String where, Object... args) {
        Object[] values = new Object[args.length + 1];
        values[0] = id;
        System.arraycopy(args, 0, values, 1, args.length);
        List<Map<String, Object>> found = jdbc.query("""
                SELECT s.id, s.shop_id, s.settlement_no, s.period_start, s.period_end, s.order_amount, s.refund_amount,
                       s.commission_amount, s.adjustment_amount, s.payable_amount, s.status, s.reconciled_at, s.paid_at,
                       h.shop_name, h.commission_rate
                FROM merchant_settlement s
                JOIN shop h ON h.id = s.shop_id
                WHERE s.id = ? AND %s
                """.formatted(where), (rs, row) -> {
            Map<String, Object> item = moneyRow(rs);
            item.put("id", rs.getLong("id"));
            item.put("shopId", rs.getLong("shop_id"));
            item.put("no", rs.getString("settlement_no"));
            item.put("shopName", rs.getString("shop_name"));
            item.put("rate", percent(rs.getBigDecimal("commission_rate")));
            LocalDate start = rs.getDate("period_start").toLocalDate();
            LocalDate end = rs.getDate("period_end").toLocalDate();
            item.put("period", start + " ~ " + end);
            item.put("periodStart", start.format(DAY));
            item.put("periodEnd", end.format(DAY));
            item.put("status", rs.getString("status"));
            item.put("statusName", statusName(rs.getString("status")));
            item.put("reconciledAt", clock(rs.getTimestamp("reconciled_at")));
            item.put("paidAt", clock(rs.getTimestamp("paid_at")));
            item.put("start", start);
            item.put("end", end);
            return item;
        }, values);
        if (found.isEmpty()) {
            throw new ApiException(404, "结算单不存在");
        }
        Map<String, Object> item = found.get(0);
        LocalDate start = (LocalDate) item.remove("start");
        LocalDate end = (LocalDate) item.remove("end");
        long shopId = (Long) item.remove("shopId");
        Timestamp from = Timestamp.valueOf(start.atStartOfDay());
        Timestamp to = Timestamp.valueOf(end.plusDays(1).atStartOfDay());
        item.put("orders", orderLines(shopId, from, to));
        item.put("refunds", refundLines(shopId, from, to));
        return item;
    }

    private Map<String, Object> brief(long id) {
        List<Map<String, Object>> found = bills("s.id = ?", id);
        if (found.isEmpty()) {
            throw new ApiException(404, "结算单不存在");
        }
        return found.get(0);
    }

    private Bill lock(long id) {
        List<Bill> found = jdbc.query("""
                SELECT id, status, order_amount, refund_amount, commission_amount
                FROM merchant_settlement WHERE id = ? FOR UPDATE
                """, (rs, row) -> new Bill(
                rs.getLong("id"),
                rs.getString("status"),
                rs.getBigDecimal("order_amount"),
                rs.getBigDecimal("refund_amount"),
                rs.getBigDecimal("commission_amount")
        ), id);
        if (found.isEmpty()) {
            throw new ApiException(404, "结算单不存在");
        }
        return found.get(0);
    }

    private List<Shop> shops() {
        return jdbc.query("""
                SELECT id, merchant_id, shop_name, commission_rate, settlement_cycle
                FROM shop ORDER BY id
                """, (rs, row) -> new Shop(
                rs.getLong("id"),
                rs.getLong("merchant_id"),
                rs.getString("shop_name"),
                rs.getBigDecimal("commission_rate"),
                rs.getString("settlement_cycle")
        ));
    }

    private Map<String, Object> preview(Shop shop, Period period) {
        Totals totals = totals(shop, period);
        BigDecimal commission = commission(totals.net(), shop.rate());
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("shopName", shop.name());
        item.put("rate", percent(shop.rate()));
        item.put("period", period.start() + " ~ " + period.end());
        item.put("orderAmount", money(totals.orderAmount()));
        item.put("refundAmount", money(totals.refundAmount()));
        item.put("commissionAmount", money(commission));
        item.put("payableAmount", money(payable(totals.orderAmount(), totals.refundAmount(), commission, BigDecimal.ZERO)));
        item.put("closed", false);
        Timestamp from = Timestamp.valueOf(period.start().atStartOfDay());
        Timestamp to = Timestamp.valueOf(period.end().plusDays(1).atStartOfDay());
        item.put("orders", orderLines(shop.id(), from, to));
        item.put("refunds", refundLines(shop.id(), from, to));
        return item;
    }

    private List<Map<String, Object>> orderLines(long shopId, Timestamp from, Timestamp to) {
        return jdbc.query("""
                SELECT order_no, paid_amount, completed_at FROM sales_order
                WHERE shop_id = ? AND completed_at >= ? AND completed_at < ?
                ORDER BY completed_at, id
                """, (rs, row) -> {
            Map<String, Object> line = new LinkedHashMap<>();
            line.put("orderNo", rs.getString("order_no"));
            line.put("amount", money(rs.getBigDecimal("paid_amount")));
            line.put("time", clock(rs.getTimestamp("completed_at")));
            return line;
        }, shopId, from, to);
    }

    private List<Map<String, Object>> refundLines(long shopId, Timestamp from, Timestamp to) {
        return jdbc.query("""
                SELECT a.after_sale_no, o.order_no, p.amount, p.paid_at,
                       CASE WHEN p.paid_at <= o.completed_at THEN 'BEFORE' ELSE 'AFTER' END AS timing
                FROM payment_record p
                JOIN after_sale a ON a.after_sale_no = p.related_no
                JOIN sales_order o ON o.id = a.order_id
                WHERE p.trade_type = 'REFUND' AND p.status = 'SUCCESS'
                  AND o.shop_id = ? AND o.completed_at IS NOT NULL
                  AND (
                    (p.paid_at <= o.completed_at AND o.completed_at >= ? AND o.completed_at < ?)
                    OR (p.paid_at > o.completed_at AND p.paid_at >= ? AND p.paid_at < ?)
                  )
                ORDER BY p.paid_at, p.id
                """, (rs, row) -> {
            Map<String, Object> line = new LinkedHashMap<>();
            line.put("no", rs.getString("after_sale_no"));
            line.put("orderNo", rs.getString("order_no"));
            line.put("amount", money(rs.getBigDecimal("amount")));
            line.put("time", clock(rs.getTimestamp("paid_at")));
            line.put("timing", "BEFORE".equals(rs.getString("timing")) ? "完成前退款" : "完成后退款");
            return line;
        }, shopId, from, to, from, to);
    }

    private static Period currentPeriod(String cycle, LocalDate today) {
        String normalized = cycle == null ? "WEEKLY" : cycle.trim().toUpperCase();
        if ("MONTHLY".equals(normalized)) {
            return new Period(today.withDayOfMonth(1), today.with(TemporalAdjusters.lastDayOfMonth()));
        }
        if ("DAILY".equals(normalized)) {
            return new Period(today, today);
        }
        LocalDate monday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        return new Period(monday, monday.plusDays(6));
    }

    private static Period previousPeriod(String cycle, LocalDate today) {
        String normalized = cycle == null ? "WEEKLY" : cycle.trim().toUpperCase();
        if ("MONTHLY".equals(normalized)) {
            LocalDate start = today.withDayOfMonth(1).minusMonths(1);
            return new Period(start, start.plusMonths(1).minusDays(1));
        }
        if ("DAILY".equals(normalized)) {
            LocalDate day = today.minusDays(1);
            return new Period(day, day);
        }
        LocalDate monday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).minusWeeks(1);
        return new Period(monday, monday.plusDays(6));
    }

    private static BigDecimal commission(BigDecimal net, BigDecimal rate) {
        if (net.signum() <= 0 || rate == null || rate.signum() <= 0) {
            return BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        }
        BigDecimal fraction = rate.compareTo(BigDecimal.ONE) > 0
                ? rate.divide(HUNDRED, 6, RoundingMode.HALF_UP)
                : rate;
        return net.multiply(fraction).setScale(2, RoundingMode.HALF_UP);
    }

    private static BigDecimal payable(BigDecimal orders, BigDecimal refunds, BigDecimal commission, BigDecimal adjustment) {
        return orders.subtract(refunds).subtract(commission).add(adjustment).setScale(2, RoundingMode.HALF_UP);
    }

    private static Map<String, Object> moneyRow(java.sql.ResultSet rs) throws java.sql.SQLException {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("orderAmount", money(rs.getBigDecimal("order_amount")));
        item.put("refundAmount", money(rs.getBigDecimal("refund_amount")));
        item.put("commissionAmount", money(rs.getBigDecimal("commission_amount")));
        item.put("adjustmentAmount", money(rs.getBigDecimal("adjustment_amount")));
        item.put("payableAmount", money(rs.getBigDecimal("payable_amount")));
        return item;
    }

    private static Map<String, Object> skip(Shop shop, String reason) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("shopName", shop.name());
        item.put("reason", reason);
        return item;
    }

    private static String percent(BigDecimal rate) {
        if (rate == null || rate.signum() == 0) {
            return "0%";
        }
        BigDecimal value = rate.compareTo(BigDecimal.ONE) > 0 ? rate : rate.multiply(HUNDRED);
        return value.stripTrailingZeros().toPlainString() + "%";
    }

    private static String statusName(String status) {
        return switch (status) {
            case "PENDING_RECONCILE" -> "待核对";
            case "RECONCILED" -> "待打款";
            case "PAID" -> "已打款";
            default -> status;
        };
    }

    private static String money(BigDecimal value) {
        return scale(value).toPlainString();
    }

    private static BigDecimal scale(BigDecimal value) {
        return (value == null ? BigDecimal.ZERO : value).setScale(2, RoundingMode.HALF_UP);
    }

    private static String clock(Timestamp value) {
        if (value == null) {
            return "";
        }
        return value.toInstant().atZone(ZONE).format(CLOCK);
    }

    private static String nextNo() {
        return "ST" + DateTimeFormatter.ofPattern("yyyyMMddHHmmss").format(LocalDateTime.now(ZONE))
                + ThreadLocalRandom.current().nextInt(1000, 10000);
    }

    public record AdjustmentRequest(BigDecimal adjustment) {
    }

    private record Shop(long id, long merchantId, String name, BigDecimal rate, String cycle) {
    }

    private record Period(LocalDate start, LocalDate end) {
    }

    private record Totals(BigDecimal orderAmount, BigDecimal refundAmount, BigDecimal net) {
    }

    private record Bill(long id, String status, BigDecimal orderAmount, BigDecimal refundAmount, BigDecimal commissionAmount) {
    }
}
