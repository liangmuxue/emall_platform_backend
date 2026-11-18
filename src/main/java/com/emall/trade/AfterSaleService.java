package com.emall.trade;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

import com.emall.api.ApiException;
import com.emall.catalog.CatalogService;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AfterSaleService {

    private static final Set<String> REFUNDABLE = Set.of("PAID", "PREPARING", "READY_PICKUP", "PICKED_UP", "COMPLETED");
    private static final Set<String> PICKED = Set.of("PICKED_UP", "COMPLETED");
    private static final Set<String> REASONS = Set.of("NOT_WANTED", "QUALITY", "MISSING", "OTHER");
    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final JdbcTemplate jdbc;
    private final CatalogService catalog;
    private final MarketingService marketing;

    public AfterSaleService(JdbcTemplate jdbc, CatalogService catalog, MarketingService marketing) {
        this.jdbc = jdbc;
        this.catalog = catalog;
        this.marketing = marketing;
    }

    public Map<String, Object> memberBoard(long memberId, long orderId) {
        OrderRow order = orderOf(memberId, orderId);
        Map<String, Object> board = new LinkedHashMap<>();
        board.put("canApply", canApply(order));
        board.put("allowReturn", PICKED.contains(order.status()));
        board.put("items", lines(order));
        board.put("cases", cases("a.member_id = ? AND a.order_id = ?", memberId, orderId));
        return board;
    }

    public Map<String, Object> quote(long memberId, long orderId, ApplyRequest request) {
        OrderRow order = orderOf(memberId, orderId);
        BigDecimal max = maxAmount(order, request.items());
        Map<String, Object> quote = new LinkedHashMap<>();
        quote.put("maxAmount", money(max));
        return quote;
    }

    @Transactional
    public Map<String, Object> apply(long memberId, long orderId, ApplyRequest request) {
        OrderRow order = lockOrder(memberId, orderId);
        if (!canApply(order)) {
            throw new ApiException(400, "这个订单现在不能申请售后");
        }
        String type = request == null ? "" : text(request.type());
        if (!"REFUND_ONLY".equals(type) && !"RETURN_REFUND".equals(type)) {
            throw new ApiException(400, "请选择售后类型");
        }
        if ("RETURN_REFUND".equals(type) && !PICKED.contains(order.status())) {
            throw new ApiException(400, "还没取货，只能申请仅退款");
        }
        String reason = request.reasonCode() == null ? "" : request.reasonCode().trim();
        if (!REASONS.contains(reason)) {
            throw new ApiException(400, "请选择售后原因");
        }
        String note = request.reasonText() == null ? "" : request.reasonText().trim();
        if (note.length() > 200) {
            throw new ApiException(400, "补充说明不能超过 200 字");
        }
        List<RefundLine> picked = selected(order, request.items());
        BigDecimal max = cap(order, picked);
        BigDecimal amount = request.amount() == null ? max : request.amount().setScale(2, RoundingMode.HALF_UP);
        if (amount.signum() <= 0 || amount.compareTo(max) > 0) {
            throw new ApiException(400, "退款金额需大于 0，且不超过 ¥" + money(max));
        }
        long id = insertCase(order, memberId, type, reason, note, amount);
        split(id, picked, amount, max);
        return detail(id, "a.member_id = ?", memberId);
    }

    @Transactional
    public Map<String, Object> tracking(long memberId, long id, String trackingNo) {
        CaseRow row = lockCase(id, "a.member_id = ?", memberId);
        if (!"WAIT_RETURN".equals(row.status())) {
            throw new ApiException(400, "当前不用填写退货单号");
        }
        String no = required(trackingNo, "请填写退货单号");
        if (no.length() > 40) {
            throw new ApiException(400, "退货单号过长");
        }
        jdbc.update("UPDATE after_sale SET return_tracking_no = ? WHERE id = ?", no, id);
        return detail(id, "a.member_id = ?", memberId);
    }

    @Transactional
    public Map<String, Object> arbitrate(long memberId, long id, String reason) {
        CaseRow row = lockCase(id, "a.member_id = ?", memberId);
        if (!"REJECTED".equals(row.status())) {
            throw new ApiException(400, "商家还没拒绝，不能申请仲裁");
        }
        String text = required(reason, "请填写申请仲裁的原因");
        if (text.length() > 200) {
            throw new ApiException(400, "仲裁原因不能超过 200 字");
        }
        jdbc.update(
                "UPDATE after_sale SET status = 'ARBITRATING', arbitration_reason = ? WHERE id = ?",
                text,
                id
        );
        return detail(id, "a.member_id = ?", memberId);
    }

    @Transactional
    public Map<String, Object> cancel(long memberId, long id) {
        CaseRow row = lockCase(id, "a.member_id = ?", memberId);
        if (!"PENDING_MERCHANT".equals(row.status())) {
            throw new ApiException(400, "商家已经处理，不能取消");
        }
        jdbc.update("UPDATE after_sale SET status = 'CLOSED' WHERE id = ?", id);
        return detail(id, "a.member_id = ?", memberId);
    }

    public List<Map<String, Object>> merchantList(long accountId) {
        long shopId = catalog.merchantShopId(accountId);
        return cases("a.shop_id = ?", shopId);
    }

    public Map<String, Object> merchantDetail(long accountId, long id) {
        long shopId = catalog.merchantShopId(accountId);
        return detail(id, "a.shop_id = ?", shopId);
    }

    @Transactional
    public Map<String, Object> agree(long accountId, long id, String reply) {
        long shopId = catalog.merchantShopId(accountId);
        CaseRow row = lockCase(id, "a.shop_id = ?", shopId);
        if (!"PENDING_MERCHANT".equals(row.status())) {
            throw new ApiException(400, "这单售后已经处理过");
        }
        String note = optional(reply);
        if ("RETURN_REFUND".equals(row.type())) {
            jdbc.update("""
                    UPDATE after_sale
                    SET status = 'WAIT_RETURN', merchant_reply = ?, merchant_handled_by = ?, merchant_handled_at = NOW()
                    WHERE id = ?
                    """, note, accountId, id);
            return detail(id, "a.shop_id = ?", shopId);
        }
        markHandled(id, accountId, note);
        refund(row, !PICKED.contains(row.orderStatus()));
        return detail(id, "a.shop_id = ?", shopId);
    }

    @Transactional
    public Map<String, Object> reject(long accountId, long id, String reply) {
        long shopId = catalog.merchantShopId(accountId);
        CaseRow row = lockCase(id, "a.shop_id = ?", shopId);
        if (!"PENDING_MERCHANT".equals(row.status()) && !"WAIT_RETURN".equals(row.status())) {
            throw new ApiException(400, "这单售后现在不能拒绝");
        }
        String note = required(reply, "请填写拒绝原因");
        if (note.length() > 200) {
            throw new ApiException(400, "拒绝原因不能超过 200 字");
        }
        jdbc.update("""
                UPDATE after_sale
                SET status = 'REJECTED', merchant_reply = ?, merchant_handled_by = ?, merchant_handled_at = NOW()
                WHERE id = ?
                """, note, accountId, id);
        return detail(id, "a.shop_id = ?", shopId);
    }

    @Transactional
    public Map<String, Object> receiveReturn(long accountId, long id) {
        long shopId = catalog.merchantShopId(accountId);
        CaseRow row = lockCase(id, "a.shop_id = ?", shopId);
        if (!"WAIT_RETURN".equals(row.status())) {
            throw new ApiException(400, "这单还没到确认收货");
        }
        if (row.trackingNo() == null || row.trackingNo().isBlank()) {
            throw new ApiException(400, "顾客还没填写退货单号");
        }
        refund(row, true);
        return detail(id, "a.shop_id = ?", shopId);
    }

    public List<Map<String, Object>> platformList() {
        return cases("1 = 1");
    }

    public Map<String, Object> platformDetail(long id) {
        return detail(id, "1 = 1");
    }

    @Transactional
    public Map<String, Object> verdict(long accountId, long id, String support, String result) {
        CaseRow row = lockCase(id, "1 = 1");
        if (!"ARBITRATING".equals(row.status())) {
            throw new ApiException(400, "这单不在仲裁中");
        }
        String decision = required(result, "请填写仲裁说明");
        if (decision.length() > 200) {
            throw new ApiException(400, "仲裁说明不能超过 200 字");
        }
        boolean buyer = "BUYER".equalsIgnoreCase(text(support));
        if (!buyer && !"MERCHANT".equalsIgnoreCase(text(support))) {
            throw new ApiException(400, "请选择支持顾客或支持商家");
        }
        jdbc.update("""
                UPDATE after_sale
                SET arbitration_result = ?, arbitrated_by = ?, arbitrated_at = NOW(),
                    status = ?
                WHERE id = ?
                """, decision, accountId, buyer ? row.status() : "CLOSED", id);
        if (buyer) {
            refund(row, "RETURN_REFUND".equals(row.type()) || !PICKED.contains(row.orderStatus()));
        }
        return detail(id, "1 = 1");
    }

    private void markHandled(long id, long accountId, String note) {
        jdbc.update("""
                UPDATE after_sale
                SET merchant_reply = ?, merchant_handled_by = ?, merchant_handled_at = NOW()
                WHERE id = ?
                """, note, accountId, id);
    }

    private void refund(CaseRow row, boolean restoreStock) {
        List<RefundLine> lines = jdbc.query("""
                SELECT i.order_item_id, i.refund_qty, i.refund_amount, k.sku_id, k.spu_id
                FROM after_sale_item i
                JOIN sales_order_item k ON k.id = i.order_item_id
                WHERE i.after_sale_id = ?
                """, (rs, index) -> new RefundLine(
                rs.getLong("order_item_id"),
                rs.getLong("sku_id"),
                rs.getLong("spu_id"),
                rs.getInt("refund_qty"),
                rs.getBigDecimal("refund_amount")
        ), row.id());
        for (RefundLine line : lines) {
            int updated = jdbc.update(
                    "UPDATE sales_order_item SET refund_qty = refund_qty + ? WHERE id = ? AND quantity >= refund_qty + ?",
                    line.quantity(),
                    line.orderItemId(),
                    line.quantity()
            );
            if (updated == 0) {
                throw new ApiException(400, "可退数量不足");
            }
            if (restoreStock) {
                restore(line, row.orderNo());
            }
        }
        adjustPoints(row);
        String paymentNo = nextNo("RF");
        jdbc.update("""
                INSERT INTO payment_record
                (payment_no, member_id, order_id, trade_type, pay_channel, amount, status, third_party_no, related_no, paid_at)
                VALUES (?, ?, ?, 'REFUND', 'WECHAT', ?, 'SUCCESS', ?, ?, NOW())
                """, paymentNo, row.memberId(), row.orderId(), row.amount(), "LOCAL-" + paymentNo, row.no());
        jdbc.update(
                "UPDATE after_sale SET status = 'REFUNDED', refund_payment_no = ? WHERE id = ?",
                paymentNo,
                row.id()
        );
        Integer left = jdbc.queryForObject(
                "SELECT COALESCE(SUM(quantity - refund_qty), 0) FROM sales_order_item WHERE order_id = ?",
                Integer.class,
                row.orderId()
        );
        if (left != null && left == 0) {
            jdbc.update(
                    "UPDATE sales_order SET order_status = 'REFUNDED' WHERE id = ? AND order_status <> 'REFUNDED'",
                    row.orderId()
            );
            jdbc.update("""
                    INSERT INTO order_status_log
                    (order_id, from_status, to_status, action, remark, operator_type)
                    VALUES (?, ?, 'REFUNDED', 'REFUND', ?, 'SYSTEM')
                    """, row.orderId(), row.orderStatus(), "售后退款完成");
            marketing.release(row.orderId());
        }
    }

    private void restore(RefundLine line, String orderNo) {
        List<Integer> stock = jdbc.query(
                "SELECT available_qty FROM inventory_stock WHERE sku_id = ? FOR UPDATE",
                (rs, index) -> rs.getInt("available_qty"),
                line.skuId()
        );
        if (stock.isEmpty()) {
            return;
        }
        int available = stock.get(0);
        int after = available + line.quantity();
        jdbc.update(
                "UPDATE inventory_stock SET available_qty = available_qty + ? WHERE sku_id = ?",
                line.quantity(),
                line.skuId()
        );
        jdbc.update("""
                INSERT INTO inventory_log
                (sku_id, change_type, change_qty, before_available_qty, after_available_qty, related_no, remark)
                VALUES (?, 'REFUND_BACK', ?, ?, ?, ?, '售后退回库存')
                """, line.skuId(), line.quantity(), available, after, orderNo);
        jdbc.update(
                "UPDATE product_spu SET sold_count = GREATEST(sold_count - ?, 0) WHERE id = ?",
                line.quantity(),
                line.spuId()
        );
    }

    private void adjustPoints(CaseRow row) {
        if (row.payable().signum() <= 0) {
            return;
        }
        int gained = sumPoints(row.memberId(), row.orderNo(), "ORDER_GAIN");
        int used = row.pointAmount().multiply(BigDecimal.valueOf(100)).setScale(0, RoundingMode.DOWN).intValue();
        int back = share(used, row.amount(), row.payable());
        int claw = share(gained, row.amount(), row.payable());
        int after = lastPoints(row.memberId());
        if (back > 0) {
            after += back;
            writePoints(row.memberId(), back, after, row.orderNo(), "退回抵现积分");
        }
        int take = Math.min(claw, Math.max(after, 0));
        if (take > 0) {
            after -= take;
            writePoints(row.memberId(), -take, after, row.orderNo(), "退款扣回消费积分");
        }
    }

    private int share(int points, BigDecimal refund, BigDecimal payable) {
        if (points <= 0) {
            return 0;
        }
        return refund.multiply(BigDecimal.valueOf(points))
                .divide(payable, 0, RoundingMode.DOWN)
                .intValue();
    }

    private int sumPoints(long memberId, String orderNo, String type) {
        Integer sum = jdbc.queryForObject("""
                SELECT COALESCE(SUM(change_points), 0) FROM point_ledger
                WHERE member_id = ? AND change_type = ? AND related_no = ?
                """, Integer.class, memberId, type, orderNo);
        return sum == null ? 0 : sum;
    }

    private int lastPoints(long memberId) {
        List<Integer> last = jdbc.query(
                "SELECT points_after FROM point_ledger WHERE member_id = ? ORDER BY id DESC LIMIT 1",
                (rs, row) -> rs.getInt("points_after"),
                memberId
        );
        return last.isEmpty() ? 0 : last.get(0);
    }

    private void writePoints(long memberId, int change, int after, String orderNo, String remark) {
        jdbc.update("""
                INSERT INTO point_ledger
                (ledger_no, member_id, change_type, change_points, point_bucket, points_after, related_no, remark)
                VALUES (?, ?, 'REFUND', ?, 'BEHAVIOR', ?, ?, ?)
                """, nextNo("PT"), memberId, change, after, orderNo, remark);
    }

    private long insertCase(OrderRow order, long memberId, String type, String reason, String note, BigDecimal amount) {
        for (int attempt = 0; attempt < 5; attempt++) {
            String no = nextNo("AS");
            try {
                GeneratedKeyHolder keys = new GeneratedKeyHolder();
                jdbc.update(connection -> {
                    var statement = connection.prepareStatement("""
                            INSERT INTO after_sale
                            (after_sale_no, order_id, member_id, shop_id, after_sale_type, reason_code, reason_text, refund_amount, status)
                            VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'PENDING_MERCHANT')
                            """, new String[]{"id"});
                    statement.setString(1, no);
                    statement.setLong(2, order.id());
                    statement.setLong(3, memberId);
                    statement.setLong(4, order.shopId());
                    statement.setString(5, type);
                    statement.setString(6, reason);
                    statement.setString(7, note.isEmpty() ? null : note);
                    statement.setBigDecimal(8, amount);
                    return statement;
                }, keys);
                return keys.getKey().longValue();
            } catch (DuplicateKeyException ignored) {
                // 单号撞了就再取一个
            }
        }
        throw new ApiException(500, "售后单号生成失败，请重试");
    }

    private void split(long afterSaleId, List<RefundLine> lines, BigDecimal amount, BigDecimal max) {
        BigDecimal left = amount;
        for (int index = 0; index < lines.size(); index++) {
            RefundLine line = lines.get(index);
            BigDecimal share = index == lines.size() - 1
                    ? left
                    : amount.multiply(line.amount()).divide(max, 2, RoundingMode.DOWN);
            left = left.subtract(share);
            jdbc.update("""
                    INSERT INTO after_sale_item (after_sale_id, order_item_id, refund_qty, refund_amount)
                    VALUES (?, ?, ?, ?)
                    """, afterSaleId, line.orderItemId(), line.quantity(), share.max(BigDecimal.ZERO));
        }
    }

    private BigDecimal maxAmount(OrderRow order, List<ItemLine> items) {
        return cap(order, selected(order, items));
    }

    private BigDecimal cap(OrderRow order, List<RefundLine> lines) {
        BigDecimal selected = BigDecimal.ZERO;
        for (RefundLine line : lines) {
            selected = selected.add(line.amount());
        }
        BigDecimal refunded = jdbc.queryForObject("""
                SELECT COALESCE(SUM(refund_amount), 0) FROM after_sale
                WHERE order_id = ? AND status = 'REFUNDED'
                """, BigDecimal.class, order.id());
        BigDecimal left = order.payable().subtract(refunded == null ? BigDecimal.ZERO : refunded).max(BigDecimal.ZERO);
        BigDecimal max = selected.min(left).setScale(2, RoundingMode.HALF_UP);
        if (max.signum() <= 0) {
            throw new ApiException(400, "这单没有可退金额了");
        }
        return max;
    }

    private List<RefundLine> selected(OrderRow order, List<ItemLine> requested) {
        if (requested == null || requested.isEmpty()) {
            throw new ApiException(400, "请选择要退的商品");
        }
        Map<Long, ItemRow> items = itemMap(order.id());
        List<RefundLine> lines = new ArrayList<>();
        for (ItemLine request : requested) {
            if (request.orderItemId() == null || request.quantity() == null || request.quantity() < 1) {
                continue;
            }
            ItemRow item = items.get(request.orderItemId());
            if (item == null) {
                throw new ApiException(400, "商品不在这个订单里");
            }
            int left = item.quantity() - item.refundedQty();
            if (request.quantity() > left) {
                throw new ApiException(400, item.name() + " 可退数量不足");
            }
            BigDecimal linePayable = order.goods().signum() == 0
                    ? BigDecimal.ZERO
                    : item.amount().multiply(order.payable()).divide(order.goods(), 2, RoundingMode.HALF_UP);
            BigDecimal refundedAmount = jdbc.queryForObject("""
                    SELECT COALESCE(SUM(i.refund_amount), 0)
                    FROM after_sale_item i
                    JOIN after_sale a ON a.id = i.after_sale_id
                    WHERE i.order_item_id = ? AND a.status = 'REFUNDED'
                    """, BigDecimal.class, item.id());
            BigDecimal leftMoney = linePayable.subtract(refundedAmount == null ? BigDecimal.ZERO : refundedAmount).max(BigDecimal.ZERO);
            BigDecimal share = request.quantity() == left
                    ? leftMoney
                    : leftMoney.multiply(BigDecimal.valueOf(request.quantity())).divide(BigDecimal.valueOf(left), 2, RoundingMode.DOWN);
            lines.add(new RefundLine(item.id(), item.skuId(), item.spuId(), request.quantity(), share));
        }
        if (lines.isEmpty()) {
            throw new ApiException(400, "请选择要退的商品");
        }
        return lines;
    }

    private boolean canApply(OrderRow order) {
        if (!REFUNDABLE.contains(order.status())) {
            return false;
        }
        Integer open = jdbc.queryForObject(
                "SELECT COUNT(*) FROM after_sale WHERE order_id = ? AND status IN ('PENDING_MERCHANT', 'WAIT_RETURN', 'ARBITRATING', 'REJECTED')",
                Integer.class,
                order.id()
        );
        return open == null || open == 0;
    }

    private List<Map<String, Object>> lines(OrderRow order) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (ItemRow item : itemMap(order.id()).values()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", item.id());
            row.put("name", item.name());
            row.put("spec", item.spec());
            row.put("quantity", item.quantity());
            row.put("refundedQty", item.refundedQty());
            row.put("leftQty", item.quantity() - item.refundedQty());
            rows.add(row);
        }
        return rows;
    }

    private Map<Long, ItemRow> itemMap(long orderId) {
        Map<Long, ItemRow> items = new LinkedHashMap<>();
        jdbc.query("""
                SELECT id, sku_id, spu_id, product_name_snapshot, spec_snapshot_json, quantity, item_amount, refund_qty
                FROM sales_order_item WHERE order_id = ? ORDER BY id
                """, rs -> {
            long id = rs.getLong("id");
            items.put(id, new ItemRow(
                    id,
                    rs.getLong("sku_id"),
                    rs.getLong("spu_id"),
                    rs.getString("product_name_snapshot"),
                    specName(rs.getString("spec_snapshot_json")),
                    rs.getInt("quantity"),
                    rs.getInt("refund_qty"),
                    rs.getBigDecimal("item_amount")
            ));
        }, orderId);
        return items;
    }

    private List<Map<String, Object>> cases(String where, Object... args) {
        return jdbc.query("""
                SELECT a.id, a.after_sale_no, a.after_sale_type, a.reason_code, a.reason_text, a.refund_amount, a.status,
                       a.merchant_reply, a.return_tracking_no, a.created_at, o.order_no, m.nickname, m.mobile, s.shop_name
                FROM after_sale a
                JOIN sales_order o ON o.id = a.order_id
                JOIN member_account m ON m.id = a.member_id
                JOIN shop s ON s.id = a.shop_id
                WHERE %s
                ORDER BY a.id DESC
                """.formatted(where), (rs, row) -> brief(rs), args);
    }

    private Map<String, Object> detail(long id, String where, Object... args) {
        Object[] values = new Object[args.length + 1];
        values[0] = id;
        System.arraycopy(args, 0, values, 1, args.length);
        List<Map<String, Object>> found = jdbc.query("""
                SELECT a.id, a.after_sale_no, a.after_sale_type, a.reason_code, a.reason_text, a.refund_amount, a.status,
                       a.merchant_reply, a.return_tracking_no, a.arbitration_reason, a.arbitration_result, a.refund_payment_no,
                       a.created_at, o.order_no, o.order_status, o.payable_amount, m.nickname, m.mobile, s.shop_name
                FROM after_sale a
                JOIN sales_order o ON o.id = a.order_id
                JOIN member_account m ON m.id = a.member_id
                JOIN shop s ON s.id = a.shop_id
                WHERE a.id = ? AND %s
                """.formatted(where), (rs, row) -> {
            Map<String, Object> item = brief(rs);
            item.put("orderStatus", rs.getString("order_status"));
            item.put("orderStatusName", TradeServiceStatus.name(rs.getString("order_status")));
            item.put("payableAmount", money(rs.getBigDecimal("payable_amount")));
            item.put("arbitrationReason", rs.getString("arbitration_reason"));
            item.put("arbitrationResult", rs.getString("arbitration_result"));
            item.put("refundNo", rs.getString("refund_payment_no"));
            return item;
        }, values);
        if (found.isEmpty()) {
            throw new ApiException(404, "售后单不存在");
        }
        Map<String, Object> item = found.get(0);
        item.put("items", jdbc.query("""
                SELECT k.product_name_snapshot, k.spec_snapshot_json, i.refund_qty, i.refund_amount
                FROM after_sale_item i
                JOIN sales_order_item k ON k.id = i.order_item_id
                WHERE i.after_sale_id = ?
                ORDER BY i.id
                """, (rs, row) -> {
            Map<String, Object> line = new LinkedHashMap<>();
            line.put("name", rs.getString("product_name_snapshot"));
            line.put("spec", specName(rs.getString("spec_snapshot_json")));
            line.put("quantity", rs.getInt("refund_qty"));
            line.put("amount", money(rs.getBigDecimal("refund_amount")));
            return line;
        }, id));
        return item;
    }

    private Map<String, Object> brief(java.sql.ResultSet rs) throws java.sql.SQLException {
        Map<String, Object> item = new LinkedHashMap<>();
        String type = rs.getString("after_sale_type");
        String status = rs.getString("status");
        item.put("id", rs.getLong("id"));
        item.put("no", rs.getString("after_sale_no"));
        item.put("type", type);
        item.put("typeName", "RETURN_REFUND".equals(type) ? "退货退款" : "仅退款");
        item.put("reasonCode", rs.getString("reason_code"));
        item.put("reasonName", reasonName(rs.getString("reason_code")));
        item.put("reasonText", rs.getString("reason_text"));
        item.put("amount", money(rs.getBigDecimal("refund_amount")));
        item.put("status", status);
        item.put("statusName", statusName(status));
        item.put("reply", rs.getString("merchant_reply"));
        item.put("trackingNo", rs.getString("return_tracking_no"));
        item.put("orderNo", rs.getString("order_no"));
        item.put("buyer", rs.getString("nickname"));
        item.put("mobile", rs.getString("mobile"));
        item.put("shopName", rs.getString("shop_name"));
        item.put("createdAt", rs.getTimestamp("created_at").toLocalDateTime().format(CLOCK));
        return item;
    }

    private OrderRow orderOf(long memberId, long orderId) {
        List<OrderRow> found = jdbc.query("""
                SELECT id, shop_id, order_no, order_status, goods_amount, payable_amount, point_discount_amount, member_id
                FROM sales_order WHERE id = ? AND member_id = ?
                """, (rs, row) -> orderRow(rs), orderId, memberId);
        if (found.isEmpty()) {
            throw new ApiException(404, "订单不存在");
        }
        return found.get(0);
    }

    private OrderRow lockOrder(long memberId, long orderId) {
        List<OrderRow> found = jdbc.query("""
                SELECT id, shop_id, order_no, order_status, goods_amount, payable_amount, point_discount_amount, member_id
                FROM sales_order WHERE id = ? AND member_id = ? FOR UPDATE
                """, (rs, row) -> orderRow(rs), orderId, memberId);
        if (found.isEmpty()) {
            throw new ApiException(404, "订单不存在");
        }
        return found.get(0);
    }

    private OrderRow orderRow(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new OrderRow(
                rs.getLong("id"),
                rs.getLong("shop_id"),
                rs.getLong("member_id"),
                rs.getString("order_no"),
                rs.getString("order_status"),
                rs.getBigDecimal("goods_amount"),
                rs.getBigDecimal("payable_amount"),
                rs.getBigDecimal("point_discount_amount")
        );
    }

    private CaseRow lockCase(long id, String where, Object... args) {
        Object[] values = new Object[args.length + 1];
        values[0] = id;
        System.arraycopy(args, 0, values, 1, args.length);
        List<CaseRow> found = jdbc.query("""
                SELECT a.id, a.after_sale_no, a.order_id, a.member_id, a.after_sale_type, a.status, a.refund_amount,
                       a.return_tracking_no, o.order_no, o.order_status, o.payable_amount, o.point_discount_amount
                FROM after_sale a
                JOIN sales_order o ON o.id = a.order_id
                WHERE a.id = ? AND %s
                FOR UPDATE
                """.formatted(where), (rs, row) -> new CaseRow(
                rs.getLong("id"),
                rs.getString("after_sale_no"),
                rs.getLong("order_id"),
                rs.getLong("member_id"),
                rs.getString("after_sale_type"),
                rs.getString("status"),
                rs.getBigDecimal("refund_amount"),
                rs.getString("return_tracking_no"),
                rs.getString("order_no"),
                rs.getString("order_status"),
                rs.getBigDecimal("payable_amount"),
                rs.getBigDecimal("point_discount_amount")
        ), values);
        if (found.isEmpty()) {
            throw new ApiException(404, "售后单不存在");
        }
        return found.get(0);
    }

    private static String specName(String json) {
        if (json == null || json.isBlank()) {
            return "";
        }
        int name = json.indexOf("\"skuName\"");
        if (name < 0) {
            return "";
        }
        int colon = json.indexOf(':', name);
        int quote = json.indexOf('"', colon + 1);
        int end = json.indexOf('"', quote + 1);
        if (quote < 0 || end < 0) {
            return "";
        }
        return json.substring(quote + 1, end);
    }

    private static String reasonName(String code) {
        return switch (code == null ? "" : code) {
            case "NOT_WANTED" -> "不想要了";
            case "QUALITY" -> "商品有问题";
            case "MISSING" -> "缺货少件";
            default -> "其他";
        };
    }

    private static String statusName(String status) {
        return switch (status) {
            case "PENDING_MERCHANT" -> "待商家处理";
            case "REJECTED" -> "商家已拒绝";
            case "ARBITRATING" -> "平台仲裁中";
            case "WAIT_RETURN" -> "待寄回";
            case "REFUNDED" -> "已退款";
            case "CLOSED" -> "已关闭";
            default -> status;
        };
    }

    private static String money(BigDecimal value) {
        return (value == null ? BigDecimal.ZERO : value).setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    private static String text(String value) {
        return value == null ? "" : value.trim().toUpperCase();
    }

    private static String optional(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.length() > 200) {
            throw new ApiException(400, "说明不能超过 200 字");
        }
        return trimmed;
    }

    private static String required(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new ApiException(400, message);
        }
        return value.trim();
    }

    private static String nextNo(String prefix) {
        return prefix + DateTimeFormatter.ofPattern("yyyyMMddHHmmss").format(LocalDateTime.now())
                + ThreadLocalRandom.current().nextInt(1000, 10000);
    }

    public record ApplyRequest(String type, String reasonCode, String reasonText, BigDecimal amount, List<ItemLine> items) {
    }

    public record ItemLine(Long orderItemId, Integer quantity) {
    }

    public record ReplyRequest(String reply) {
    }

    public record VerdictRequest(String support, String result) {
    }

    private record OrderRow(
            long id,
            long shopId,
            long memberId,
            String orderNo,
            String status,
            BigDecimal goods,
            BigDecimal payable,
            BigDecimal pointAmount
    ) {
    }

    private record ItemRow(
            long id,
            long skuId,
            long spuId,
            String name,
            String spec,
            int quantity,
            int refundedQty,
            BigDecimal amount
    ) {
    }

    private record RefundLine(long orderItemId, long skuId, long spuId, int quantity, BigDecimal amount) {
    }

    private record CaseRow(
            long id,
            String no,
            long orderId,
            long memberId,
            String type,
            String status,
            BigDecimal amount,
            String trackingNo,
            String orderNo,
            String orderStatus,
            BigDecimal payable,
            BigDecimal pointAmount
    ) {
    }

    private static final class TradeServiceStatus {
        private static String name(String status) {
            return switch (status == null ? "" : status) {
                case "PENDING_PAY" -> "待付款";
                case "PAID" -> "待备货";
                case "PREPARING" -> "备货中";
                case "READY_PICKUP" -> "待自提";
                case "PICKED_UP" -> "已自提";
                case "COMPLETED" -> "已完成";
                case "CANCELLED" -> "已关闭";
                case "REFUNDED" -> "已退款";
                default -> status;
            };
        }
    }
}
