package com.emall.catalog;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.emall.api.ApiException;
import com.emall.auth.Session;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CatalogService {

    private static final SecureRandom SPU_RANDOM = new SecureRandom();

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public CatalogService(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public List<Map<String, Object>> categories(boolean enabledOnly) {
        String sql = """
                SELECT id, parent_id, category_name, category_path, sort_no, status
                FROM product_category
                """;
        if (enabledOnly) {
            sql += " WHERE status = 'ENABLED'";
        }
        sql += " ORDER BY sort_no, id";
        List<Map<String, Object>> rows = jdbc.query(sql, (rs, row) -> categoryRow(rs.getLong("id"),
                nullableLong(rs, "parent_id"),
                rs.getString("category_name"),
                rs.getString("category_path"),
                rs.getInt("sort_no"),
                rs.getString("status")));
        if (!enabledOnly) {
            return rows;
        }
        return visibleCategories(rows);
    }

    @Transactional
    public Map<String, Object> saveCategory(Long id, String name, Long parentId, Integer sortNo, String status) {
        String categoryName = required(name, "请填写类目名称");
        if (categoryName.length() > 64) {
            throw new ApiException(400, "类目名称过长");
        }
        int sort = sortNo == null ? 0 : sortNo;
        String nextStatus = status == null || status.isBlank() ? "ENABLED" : status;
        if (!"ENABLED".equals(nextStatus) && !"DISABLED".equals(nextStatus)) {
            throw new ApiException(400, "类目状态不正确");
        }
        if (id != null && id.equals(parentId)) {
            throw new ApiException(400, "上级类目不能是自己");
        }
        String parentPath = "/";
        if (parentId != null) {
            parentPath = categoryPath(parentId);
            if (id != null && parentPath.contains("/" + id + "/")) {
                throw new ApiException(400, "不能把类目移到自己的下级");
            }
        }
        if (id != null) {
            categoryPath(id);
            Long oldParent = jdbc.query(
                    "SELECT parent_id FROM product_category WHERE id = ?",
                    (rs, row) -> nullableLong(rs, "parent_id"),
                    id
            ).get(0);
            Integer children = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM product_category WHERE parent_id = ?",
                    Integer.class,
                    id
            );
            if (children != null && children > 0 && !java.util.Objects.equals(oldParent, parentId)) {
                throw new ApiException(400, "有下级类目时不能改上级");
            }
        }
        assertUniqueName(id, parentId, categoryName);
        if (id == null) {
            GeneratedKeyHolder keys = new GeneratedKeyHolder();
            jdbc.update(connection -> {
                var statement = connection.prepareStatement(
                        "INSERT INTO product_category (parent_id, category_name, category_path, sort_no, status) VALUES (?, ?, '/', ?, ?)",
                        new String[]{"id"}
                );
                if (parentId == null) {
                    statement.setObject(1, null);
                } else {
                    statement.setLong(1, parentId);
                }
                statement.setString(2, categoryName);
                statement.setInt(3, sort);
                statement.setString(4, nextStatus);
                return statement;
            }, keys);
            long created = keys.getKey().longValue();
            String path = parentPath + created + "/";
            jdbc.update("UPDATE product_category SET category_path = ? WHERE id = ?", path, created);
            return category(created);
        }
        categoryPath(id);
        jdbc.update(
                "UPDATE product_category SET parent_id = ?, category_name = ?, category_path = ?, sort_no = ?, status = ? WHERE id = ?",
                parentId,
                categoryName,
                parentPath + id + "/",
                sort,
                nextStatus,
                id
        );
        return category(id);
    }

    @Transactional
    public void deleteCategory(long id) {
        Integer children = jdbc.queryForObject(
                "SELECT COUNT(*) FROM product_category WHERE parent_id = ?",
                Integer.class,
                id
        );
        if (children != null && children > 0) {
            throw new ApiException(400, "请先删除下级类目");
        }
        Integer goods = jdbc.queryForObject(
                "SELECT COUNT(*) FROM product_spu WHERE category_id = ?",
                Integer.class,
                id
        );
        if (goods != null && goods > 0) {
            throw new ApiException(400, "该类目下已有商品，不能删除");
        }
        jdbc.update("DELETE FROM product_category WHERE id = ?", id);
    }

    public List<Map<String, Object>> products(Long shopId, String status) {
        StringBuilder sql = new StringBuilder("""
                SELECT s.id, s.spu_no, s.product_name, s.sales_status, s.audit_remark, s.sold_count, s.category_id,
                       c.category_name, shop.shop_name, s.created_at
                FROM product_spu s
                JOIN product_category c ON c.id = s.category_id
                JOIN shop ON shop.id = s.shop_id
                WHERE 1 = 1
                """);
        List<Object> args = new ArrayList<>();
        if (shopId != null) {
            sql.append(" AND s.shop_id = ?");
            args.add(shopId);
        }
        if (status != null && !status.isBlank() && !"ALL".equals(status)) {
            sql.append(" AND s.sales_status = ?");
            args.add(status);
        }
        sql.append(" ORDER BY s.id DESC");
        List<Map<String, Object>> rows = jdbc.query(sql.toString(), (rs, row) -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", rs.getLong("id"));
            item.put("spuNo", rs.getString("spu_no"));
            item.put("productName", rs.getString("product_name"));
            item.put("salesStatus", rs.getString("sales_status"));
            item.put("statusName", statusName(rs.getString("sales_status")));
            item.put("auditRemark", rs.getString("audit_remark"));
            item.put("soldCount", rs.getInt("sold_count"));
            item.put("categoryId", rs.getLong("category_id"));
            item.put("categoryName", rs.getString("category_name"));
            item.put("shopName", rs.getString("shop_name"));
            item.put("createdAt", String.valueOf(rs.getTimestamp("created_at")));
            return item;
        }, args.toArray());
        attachListPrice(rows);
        return rows;
    }

    public Map<String, Object> product(long id, Long shopId) {
        List<Map<String, Object>> found = jdbc.query("""
                SELECT s.id, s.shop_id, s.spu_no, s.product_name, s.subtitle, s.detail_html, s.service_json, s.sales_status,
                       s.audit_remark, s.sold_count, s.fake_sold_count, s.category_id, c.category_name, shop.shop_name
                FROM product_spu s
                JOIN product_category c ON c.id = s.category_id
                JOIN shop ON shop.id = s.shop_id
                WHERE s.id = ?
                """, (rs, row) -> {
            if (shopId != null && rs.getLong("shop_id") != shopId) {
                throw new ApiException(403, "不能查看其他店铺的商品");
            }
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", rs.getLong("id"));
            item.put("shopId", rs.getLong("shop_id"));
            item.put("spuNo", rs.getString("spu_no"));
            item.put("productName", rs.getString("product_name"));
            item.put("subtitle", rs.getString("subtitle"));
            item.put("detail", rs.getString("detail_html"));
            item.put("services", servicesHtml(rs.getString("service_json")));
            item.put("specImages", specImages(rs.getString("service_json")));
            item.put("salesStatus", rs.getString("sales_status"));
            item.put("statusName", statusName(rs.getString("sales_status")));
            item.put("auditRemark", rs.getString("audit_remark"));
            item.put("soldCount", rs.getInt("sold_count"));
            item.put("fakeSold", rs.getInt("fake_sold_count"));
            item.put("categoryId", rs.getLong("category_id"));
            item.put("categoryName", rs.getString("category_name"));
            item.put("shopName", rs.getString("shop_name"));
            return item;
        }, id);
        if (found.isEmpty()) {
            throw new ApiException(404, "商品不存在");
        }
        Map<String, Object> item = found.get(0);
        item.put("imageUrls", imageUrls(id));
        item.put("skus", skus(id));
        return item;
    }

    public Map<String, Object> consumerProduct(long id, long memberId) {
        Map<String, Object> item = product(id, null);
        if (!"ON_SALE".equals(item.get("salesStatus"))) {
            throw new ApiException(404, "商品不存在");
        }
        int sold = ((Number) item.get("soldCount")).intValue() + ((Number) item.get("fakeSold")).intValue();
        item.put("soldCount", sold);
        item.remove("fakeSold");
        item.remove("auditRemark");
        item.remove("salesStatus");
        item.remove("statusName");
        item.put("serviceText", plainText(String.valueOf(item.getOrDefault("services", ""))));
        long shopId = ((Number) item.get("shopId")).longValue();
        jdbc.query("""
                SELECT address, cutoff_time FROM shop WHERE id = ?
                """, rs -> {
            item.put("shopAddress", rs.getString("address"));
            String cutoff = rs.getString("cutoff_time");
            item.put("cutoff", cutoff == null || cutoff.length() < 5 ? "" : cutoff.substring(0, 5));
        }, shopId);
        Integer favored = jdbc.queryForObject(
                "SELECT COUNT(*) FROM product_favorite WHERE member_id = ? AND spu_id = ?",
                Integer.class,
                memberId,
                id
        );
        item.put("favorited", favored != null && favored > 0);
        item.put("reviews", reviews(id));
        return item;
    }

    @Transactional
    public Map<String, Object> toggleFavorite(long memberId, long spuId) {
        List<String> status = jdbc.query(
                "SELECT sales_status FROM product_spu WHERE id = ?",
                (rs, row) -> rs.getString("sales_status"),
                spuId
        );
        if (status.isEmpty() || !"ON_SALE".equals(status.get(0))) {
            throw new ApiException(404, "商品不存在");
        }
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM product_favorite WHERE member_id = ? AND spu_id = ?",
                Integer.class,
                memberId,
                spuId
        );
        boolean favored = count != null && count > 0;
        if (favored) {
            jdbc.update("DELETE FROM product_favorite WHERE member_id = ? AND spu_id = ?", memberId, spuId);
        } else {
            jdbc.update("INSERT INTO product_favorite (member_id, spu_id) VALUES (?, ?)", memberId, spuId);
        }
        return Map.of("favorited", !favored);
    }

    private List<Map<String, Object>> reviews(long spuId) {
        return jdbc.query("""
                SELECT r.score, r.review_content, r.created_at, m.nickname
                FROM product_review r
                JOIN member_account m ON m.id = r.member_id
                WHERE r.spu_id = ? AND r.status = 'VISIBLE'
                ORDER BY r.id DESC
                LIMIT 20
                """, (rs, row) -> {
            Map<String, Object> review = new LinkedHashMap<>();
            review.put("user", rs.getString("nickname"));
            review.put("score", rs.getInt("score"));
            review.put("text", rs.getString("review_content"));
            review.put("date", String.valueOf(rs.getTimestamp("created_at")).substring(0, 10));
            return review;
        }, spuId);
    }

    private static String plainText(String html) {
        if (html == null || html.isBlank()) {
            return "";
        }
        return html.replaceAll("<[^>]+>", " ")
                .replace("&nbsp;", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    @Transactional
    public Map<String, Object> saveProduct(Session session, Long id, ProductDraft draft) {
        long shopId = merchantShopId(session.accountId());
        String name = required(draft.productName(), "请填写商品名称");
        if (name.length() > 60) {
            throw new ApiException(400, "商品名称不能超过 60 个字");
        }
        if (draft.categoryId() == null) {
            throw new ApiException(400, "请选择所属类目");
        }
        assertEnabledCategory(draft.categoryId());
        int fakeSold = draft.fakeSold() == null ? 0 : draft.fakeSold();
        if (fakeSold < 0) {
            throw new ApiException(400, "基础已售需为非负整数");
        }
        List<String> images = draft.imageUrls() == null ? List.of() : draft.imageUrls().stream()
                .filter(url -> url != null && !url.isBlank())
                .map(String::trim)
                .limit(6)
                .toList();
        if (images.size() > 5) {
            throw new ApiException(400, "商品图片最多 5 张");
        }
        List<SkuDraft> specs = normalizeSpecs(draft.skus(), draft.specImages() == null || draft.specImages());
        String detail = sanitizeHtml(draft.detail());
        String services = serviceJson(sanitizeHtml(draft.services()), draft.specImages() == null || draft.specImages());
        if (id == null) {
            long created = insertProduct(session, shopId, draft.categoryId(), name, detail, services, fakeSold);
            replaceImages(created, images, session.accountId());
            replaceSkus(created, specs);
            return product(created, shopId);
        }
        salesStatus(id, shopId);
        jdbc.update("""
                UPDATE product_spu
                SET category_id = ?, product_name = ?, detail_html = ?, service_json = ?, fake_sold_count = ?
                WHERE id = ? AND shop_id = ?
                """, draft.categoryId(), name, blankToNull(detail), services, fakeSold, id, shopId);
        replaceImages(id, images, session.accountId());
        replaceSkus(id, specs);
        return product(id, shopId);
    }

    @Transactional
    public void submit(Session session, long id) {
        long shopId = merchantShopId(session.accountId());
        String current = salesStatus(id, shopId);
        if (!canEdit(current)) {
            throw new ApiException(400, "当前状态不能提交审核");
        }
        Integer skuCount = jdbc.queryForObject("SELECT COUNT(*) FROM product_sku WHERE spu_id = ?", Integer.class, id);
        if (skuCount == null || skuCount == 0) {
            throw new ApiException(400, "至少填写一个规格");
        }
        jdbc.update(
                "UPDATE product_spu SET sales_status = 'PENDING', audit_remark = NULL WHERE id = ? AND shop_id = ?",
                id,
                shopId
        );
    }

    @Transactional
    public void audit(Session session, long id, String result, String remark) {
        String current = salesStatus(id, null);
        String next = result == null ? "" : result;
        boolean rejectNeedsReason = "REJECTED".equals(next) || "OFF_SHELF".equals(next);
        if (rejectNeedsReason && (remark == null || remark.isBlank())) {
            throw new ApiException(400, "驳回或下架时请填写原因");
        }
        boolean allowed = ("PENDING".equals(current) && ("ON_SALE".equals(next) || "REJECTED".equals(next)))
                || ("ON_SALE".equals(current) && "OFF_SHELF".equals(next));
        if (!allowed) {
            throw new ApiException(400, "这个审核结果不能用在当前状态");
        }
        jdbc.update("""
                UPDATE product_spu
                SET sales_status = ?, audit_remark = ?, audited_by = ?, audited_at = NOW()
                WHERE id = ?
                """, next, blankToNull(remark), session.accountId(), id);
    }

    public List<Map<String, Object>> shelf(Long categoryId, String keyword) {
        List<Map<String, Object>> visible = visibleCategories(categories(false));
        List<Long> scope = new ArrayList<>();
        for (Map<String, Object> category : visible) {
            if (categoryId == null || categoryId.equals(category.get("id")) || pathContains(category, categoryId)) {
                scope.add((Long) category.get("id"));
            }
        }
        if (scope.isEmpty()) {
            return List.of();
        }
        StringBuilder sql = new StringBuilder("""
                SELECT s.id, s.product_name, s.subtitle, s.sold_count + s.fake_sold_count AS sold_count, s.category_id, shop.shop_name
                FROM product_spu s
                JOIN shop ON shop.id = s.shop_id
                WHERE s.sales_status = 'ON_SALE' AND s.category_id IN (
                """);
        sql.append("?,".repeat(scope.size()));
        sql.setLength(sql.length() - 1);
        sql.append(")");
        List<Object> args = new ArrayList<>(scope);
        if (keyword != null && !keyword.isBlank()) {
            sql.append(" AND s.product_name LIKE ?");
            args.add("%" + keyword.trim() + "%");
        }
        sql.append(" ORDER BY s.id DESC");
        List<Map<String, Object>> rows = jdbc.query(sql.toString(), (rs, row) -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", rs.getLong("id"));
            item.put("name", rs.getString("product_name"));
            item.put("subtitle", rs.getString("subtitle"));
            item.put("soldCount", rs.getInt("sold_count"));
            item.put("categoryId", rs.getLong("category_id"));
            item.put("shopName", rs.getString("shop_name"));
            return item;
        }, args.toArray());
        attachListPrice(rows);
        return rows;
    }

    public long merchantShopId(long accountId) {
        List<Long> shops = jdbc.query(
                "SELECT shop_id FROM merchant_account WHERE id = ? AND status = 'ENABLED'",
                (rs, row) -> nullableLong(rs, "shop_id"),
                accountId
        );
        if (shops.isEmpty() || shops.get(0) == null) {
            throw new ApiException(403, "这个账号还没有绑定店铺");
        }
        return shops.get(0);
    }

    private void attachListPrice(List<Map<String, Object>> rows) {
        if (rows.isEmpty()) {
            return;
        }
        List<Long> ids = rows.stream().map(item -> (Long) item.get("id")).toList();
        StringBuilder sql = new StringBuilder("""
                SELECT k.spu_id, MIN(k.sale_price) min_price
                FROM product_sku k
                WHERE k.status = 'ENABLED'
                  AND NOT EXISTS (SELECT 1 FROM product_sku child WHERE child.parent_sku_id = k.id)
                  AND k.spu_id IN (
                """);
        sql.append("?,".repeat(ids.size()));
        sql.setLength(sql.length() - 1);
        sql.append(") GROUP BY k.spu_id");
        Map<Long, BigDecimal> prices = new LinkedHashMap<>();
        Map<Long, BigDecimal> markets = new LinkedHashMap<>();
        jdbc.query(sql.toString(), rs -> {
            prices.put(rs.getLong("spu_id"), rs.getBigDecimal("min_price"));
        }, ids.toArray());
        StringBuilder marketSql = new StringBuilder("""
                SELECT k.spu_id, k.market_price, k.member_price
                FROM product_sku k
                JOIN (
                    SELECT leaf.spu_id, MIN(leaf.sale_price) min_price
                    FROM product_sku leaf
                    WHERE leaf.status = 'ENABLED'
                      AND NOT EXISTS (SELECT 1 FROM product_sku child WHERE child.parent_sku_id = leaf.id)
                      AND leaf.spu_id IN (
                """);
        marketSql.append("?,".repeat(ids.size()));
        marketSql.setLength(marketSql.length() - 1);
        marketSql.append(") GROUP BY leaf.spu_id) m ON m.spu_id = k.spu_id AND k.sale_price = m.min_price AND NOT EXISTS (SELECT 1 FROM product_sku child WHERE child.parent_sku_id = k.id)");
        Map<Long, BigDecimal> members = new LinkedHashMap<>();
        jdbc.query(marketSql.toString(), rs -> {
            markets.putIfAbsent(rs.getLong("spu_id"), rs.getBigDecimal("market_price"));
            members.putIfAbsent(rs.getLong("spu_id"), rs.getBigDecimal("member_price"));
        }, ids.toArray());
        Map<Long, Integer> stocks = new LinkedHashMap<>();
        StringBuilder stockSql = new StringBuilder("""
                SELECT k.spu_id, SUM(IFNULL(st.available_qty, 0)) total_stock
                FROM product_sku k
                LEFT JOIN inventory_stock st ON st.sku_id = k.id
                WHERE k.status = 'ENABLED'
                  AND NOT EXISTS (SELECT 1 FROM product_sku child WHERE child.parent_sku_id = k.id)
                  AND k.spu_id IN (
                """);
        stockSql.append("?,".repeat(ids.size()));
        stockSql.setLength(stockSql.length() - 1);
        stockSql.append(") GROUP BY k.spu_id");
        jdbc.query(stockSql.toString(), rs -> {
            stocks.put(rs.getLong("spu_id"), rs.getInt("total_stock"));
        }, ids.toArray());
        Map<Long, Integer> leafCounts = new LinkedHashMap<>();
        Map<Long, Boolean> severalPrices = new LinkedHashMap<>();
        Map<Long, String> specNames = new LinkedHashMap<>();
        StringBuilder specSql = new StringBuilder("""
                SELECT k.spu_id, COUNT(*) leaf_count, COUNT(DISTINCT k.sale_price) price_kinds,
                       GROUP_CONCAT(k.sku_name ORDER BY k.sort_no, k.id SEPARATOR ' / ') spec_names
                FROM product_sku k
                WHERE k.status = 'ENABLED'
                  AND NOT EXISTS (SELECT 1 FROM product_sku child WHERE child.parent_sku_id = k.id)
                  AND k.spu_id IN (
                """);
        specSql.append("?,".repeat(ids.size()));
        specSql.setLength(specSql.length() - 1);
        specSql.append(") GROUP BY k.spu_id");
        jdbc.query(specSql.toString(), rs -> {
            long spuId = rs.getLong("spu_id");
            leafCounts.put(spuId, rs.getInt("leaf_count"));
            severalPrices.put(spuId, rs.getInt("price_kinds") > 1);
            specNames.put(spuId, rs.getString("spec_names"));
        }, ids.toArray());
        Map<Long, String> covers = new LinkedHashMap<>();
        StringBuilder images = new StringBuilder("""
                SELECT biz_id, file_url FROM file_asset
                WHERE biz_type = 'PRODUCT' AND biz_id IN (
                """);
        images.append("?,".repeat(ids.size()));
        images.setLength(images.length() - 1);
        images.append(") ORDER BY sort_no, id");
        jdbc.query(images.toString(), rs -> {
            covers.putIfAbsent(rs.getLong("biz_id"), rs.getString("file_url"));
        }, ids.toArray());
        for (Map<String, Object> row : rows) {
            Long id = (Long) row.get("id");
            row.put("price", money(prices.get(id)));
            row.put("marketPrice", money(markets.get(id)));
            row.put("memberPrice", money(members.get(id)));
            row.put("stock", stocks.getOrDefault(id, 0));
            row.put("coverUrl", covers.get(id));
            row.put("skuCount", leafCounts.getOrDefault(id, 0));
            row.put("priceFrom", severalPrices.getOrDefault(id, false));
            row.put("specSummary", specNames.get(id));
        }
    }

    private List<Map<String, Object>> skus(long spuId) {
        return jdbc.query("""
                SELECT k.id, k.parent_sku_id, k.sku_name, k.unit_name, k.sale_price, k.market_price, k.member_price, k.main_image_url,
                       st.available_qty
                FROM product_sku k
                LEFT JOIN inventory_stock st ON st.sku_id = k.id
                WHERE k.spu_id = ?
                ORDER BY k.sort_no, k.id
                """, (rs, row) -> {
            Map<String, Object> sku = new LinkedHashMap<>();
            sku.put("id", rs.getLong("id"));
            sku.put("key", String.valueOf(rs.getLong("id")));
            sku.put("parentId", nullableLong(rs, "parent_sku_id"));
            sku.put("parentKey", rs.getObject("parent_sku_id") == null ? "" : String.valueOf(nullableLong(rs, "parent_sku_id")));
            sku.put("skuName", rs.getString("sku_name"));
            sku.put("unitName", rs.getString("unit_name"));
            sku.put("salePrice", money(rs.getBigDecimal("sale_price")));
            sku.put("marketPrice", money(rs.getBigDecimal("market_price")));
            sku.put("memberPrice", money(rs.getBigDecimal("member_price")));
            sku.put("imageUrl", rs.getString("main_image_url"));
            sku.put("stock", rs.getObject("available_qty") == null ? null : rs.getInt("available_qty"));
            return sku;
        }, spuId);
    }

    private List<String> imageUrls(long spuId) {
        return jdbc.query(
                "SELECT file_url FROM file_asset WHERE biz_type = 'PRODUCT' AND biz_id = ? ORDER BY sort_no, id",
                (rs, row) -> rs.getString("file_url"),
                spuId
        );
    }

    private void replaceImages(long spuId, List<String> urls, long accountId) {
        jdbc.update("DELETE FROM file_asset WHERE biz_type = 'PRODUCT' AND biz_id = ?", spuId);
        if (urls == null) {
            return;
        }
        int sort = 0;
        for (String url : urls) {
            if (url == null || url.isBlank()) {
                continue;
            }
            jdbc.update("""
                    INSERT INTO file_asset (biz_type, biz_id, file_type, file_url, sort_no, uploader_type, uploader_ref_id)
                    VALUES ('PRODUCT', ?, 'IMAGE', ?, ?, 'MERCHANT', ?)
                    """, spuId, url.trim(), sort++, accountId);
        }
    }

    private void replaceSkus(long spuId, List<SkuDraft> specs) {
        jdbc.update("UPDATE product_sku SET parent_sku_id = NULL WHERE spu_id = ?", spuId);
        List<Long> oldIds = jdbc.query("SELECT id FROM product_sku WHERE spu_id = ?", (rs, row) -> rs.getLong("id"), spuId);
        if (!oldIds.isEmpty()) {
            String marks = "?,".repeat(oldIds.size());
            marks = marks.substring(0, marks.length() - 1);
            jdbc.update("DELETE FROM inventory_stock WHERE sku_id IN (" + marks + ")", oldIds.toArray());
            jdbc.update("DELETE FROM product_sku WHERE spu_id = ?", spuId);
        }
        Map<String, Long> saved = new LinkedHashMap<>();
        int sort = 0;
        for (SkuDraft spec : specs) {
            Long parentId = spec.parentKey() == null || spec.parentKey().isBlank() ? null : saved.get(spec.parentKey());
            GeneratedKeyHolder keys = new GeneratedKeyHolder();
            int currentSort = sort++;
            jdbc.update(connection -> {
                var statement = connection.prepareStatement("""
                        INSERT INTO product_sku (spu_id, parent_sku_id, sku_no, sku_name, sort_no, unit_name, sale_price, market_price, member_price, main_image_url, status)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'ENABLED')
                        """, new String[]{"id"});
                statement.setLong(1, spuId);
                if (parentId == null) {
                    statement.setObject(2, null);
                } else {
                    statement.setLong(2, parentId);
                }
                statement.setString(3, "SK" + System.nanoTime());
                statement.setString(4, spec.skuName());
                statement.setInt(5, currentSort);
                statement.setString(6, spec.unitName());
                statement.setBigDecimal(7, spec.salePrice());
                statement.setBigDecimal(8, spec.marketPrice());
                statement.setBigDecimal(9, spec.memberPrice());
                statement.setString(10, blankToNull(spec.imageUrl()));
                return statement;
            }, keys);
            long skuId = keys.getKey().longValue();
            saved.put(spec.key(), skuId);
            if (spec.stock() != null) {
                jdbc.update(
                        "INSERT INTO inventory_stock (sku_id, available_qty, locked_qty, warning_qty) VALUES (?, ?, 0, 0)",
                        skuId,
                        spec.stock()
                );
            }
        }
    }

    private String salesStatus(long id, Long shopId) {
        String sql = shopId == null
                ? "SELECT sales_status FROM product_spu WHERE id = ?"
                : "SELECT sales_status FROM product_spu WHERE id = ? AND shop_id = ?";
        List<String> rows = shopId == null
                ? jdbc.query(sql, (rs, row) -> rs.getString(1), id)
                : jdbc.query(sql, (rs, row) -> rs.getString(1), id, shopId);
        if (rows.isEmpty()) {
            throw new ApiException(404, "商品不存在");
        }
        return rows.get(0);
    }

    private static boolean canEdit(String status) {
        return "DRAFT".equals(status) || "REJECTED".equals(status) || "OFF_SHELF".equals(status);
    }

    private void assertEnabledCategory(long id) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM product_category WHERE id = ? AND status = 'ENABLED'",
                Integer.class,
                id
        );
        if (count == null || count == 0) {
            throw new ApiException(400, "请选择启用中的类目");
        }
    }

    private void assertUniqueName(Long id, Long parentId, String name) {
        Integer count = parentId == null
                ? jdbc.queryForObject(
                "SELECT COUNT(*) FROM product_category WHERE parent_id IS NULL AND category_name = ? AND (? IS NULL OR id <> ?)",
                Integer.class, name, id, id)
                : jdbc.queryForObject(
                "SELECT COUNT(*) FROM product_category WHERE parent_id = ? AND category_name = ? AND (? IS NULL OR id <> ?)",
                Integer.class, parentId, name, id, id);
        if (count != null && count > 0) {
            throw new ApiException(400, "同一上级下类目不能重名");
        }
    }

    private String categoryPath(long id) {
        List<String> paths = jdbc.query(
                "SELECT category_path FROM product_category WHERE id = ?",
                (rs, row) -> rs.getString(1),
                id
        );
        if (paths.isEmpty()) {
            throw new ApiException(404, "类目不存在");
        }
        String path = paths.get(0);
        return path == null || path.isBlank() ? "/" : path;
    }

    private Map<String, Object> category(long id) {
        return jdbc.queryForObject("""
                SELECT id, parent_id, category_name, category_path, sort_no, status
                FROM product_category WHERE id = ?
                """, (rs, row) -> categoryRow(rs.getLong("id"), nullableLong(rs, "parent_id"),
                rs.getString("category_name"), rs.getString("category_path"), rs.getInt("sort_no"), rs.getString("status")), id);
    }

    private static Long nullableLong(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
        Object value = rs.getObject(column);
        if (value == null) {
            return null;
        }
        return ((Number) value).longValue();
    }

    private static Map<String, Object> categoryRow(long id, Long parentId, String name, String path, int sort, String status) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", id);
        row.put("parentId", parentId);
        row.put("name", name);
        row.put("path", path);
        row.put("sortNo", sort);
        row.put("status", status);
        row.put("statusName", "ENABLED".equals(status) ? "启用" : "已停用");
        return row;
    }

    private static List<Map<String, Object>> visibleCategories(List<Map<String, Object>> rows) {
        Map<Long, Map<String, Object>> byId = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            byId.put((Long) row.get("id"), row);
        }
        List<Map<String, Object>> visible = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            if (!"ENABLED".equals(row.get("status"))) {
                continue;
            }
            if (ancestorsEnabled(row, byId)) {
                visible.add(row);
            }
        }
        return visible;
    }

    private static boolean ancestorsEnabled(Map<String, Object> row, Map<Long, Map<String, Object>> byId) {
        Long parentId = (Long) row.get("parentId");
        while (parentId != null) {
            Map<String, Object> parent = byId.get(parentId);
            if (parent == null || !"ENABLED".equals(parent.get("status"))) {
                return false;
            }
            parentId = (Long) parent.get("parentId");
        }
        return true;
    }

    private static boolean pathContains(Map<String, Object> category, long ancestorId) {
        String path = String.valueOf(category.get("path"));
        return path.contains("/" + ancestorId + "/");
    }

    private static String statusName(String status) {
        return switch (status) {
            case "DRAFT" -> "草稿";
            case "PENDING" -> "待审核";
            case "ON_SALE" -> "已上架";
            case "REJECTED" -> "已驳回";
            case "OFF_SHELF" -> "已下架";
            default -> status;
        };
    }

    private static String required(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new ApiException(400, message);
        }
        return value.trim();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static BigDecimal moneyValue(BigDecimal value, String message) {
        if (value == null) {
            throw new ApiException(400, message);
        }
        return value.setScale(2, RoundingMode.HALF_UP);
    }

    private static BigDecimal optionalMoney(BigDecimal value) {
        return value == null ? null : value.setScale(2, RoundingMode.HALF_UP);
    }

    private static BigDecimal money(BigDecimal value) {
        return value == null ? null : value.setScale(2, RoundingMode.HALF_UP);
    }

    public Map<String, Object> shopProfile(long accountId) {
        long shopId = merchantShopId(accountId);
        return jdbc.queryForObject("""
                SELECT shop_name, cutoff_time FROM shop WHERE id = ?
                """, (rs, row) -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("shopName", rs.getString("shop_name"));
            String cutoff = rs.getString("cutoff_time");
            item.put("cutoff", cutoff == null || cutoff.length() < 5 ? "" : cutoff.substring(0, 5));
            return item;
        }, shopId);
    }

    private long insertProduct(
            Session session,
            long shopId,
            long categoryId,
            String name,
            String detail,
            String services,
            int fakeSold
    ) {
        for (int attempt = 0; attempt < 5; attempt++) {
            String spuNo = "SP" + System.currentTimeMillis() + (100 + SPU_RANDOM.nextInt(900));
            try {
                GeneratedKeyHolder keys = new GeneratedKeyHolder();
                jdbc.update(connection -> {
                    var statement = connection.prepareStatement("""
                            INSERT INTO product_spu (shop_id, category_id, spu_no, product_name, detail_html, service_json, sales_status, fake_sold_count, created_by)
                            VALUES (?, ?, ?, ?, ?, ?, 'DRAFT', ?, ?)
                            """, new String[]{"id"});
                    statement.setLong(1, shopId);
                    statement.setLong(2, categoryId);
                    statement.setString(3, spuNo);
                    statement.setString(4, name);
                    statement.setString(5, blankToNull(detail));
                    statement.setString(6, services);
                    statement.setInt(7, fakeSold);
                    statement.setLong(8, session.accountId());
                    return statement;
                }, keys);
                return keys.getKey().longValue();
            } catch (DuplicateKeyException ignored) {
                // spu_no 有唯一索引，同一毫秒撞号时换一个再写
            }
        }
        throw new ApiException(500, "商品货号生成失败，请重试");
    }

    private List<SkuDraft> normalizeSpecs(List<SkuDraft> raw, boolean keepImages) {
        if (raw == null || raw.isEmpty()) {
            throw new ApiException(400, "请至少添加一个规格商品");
        }
        List<SkuDraft> specs = new ArrayList<>();
        int index = 0;
        for (SkuDraft spec : raw) {
            String key = spec.key() == null || spec.key().isBlank() ? "k" + index : spec.key().trim();
            String parent = spec.parentKey() == null ? "" : spec.parentKey().trim();
            specs.add(new SkuDraft(
                    key,
                    parent,
                    spec.skuName(),
                    spec.unitName(),
                    spec.salePrice(),
                    spec.marketPrice(),
                    spec.memberPrice(),
                    keepImages ? spec.imageUrl() : "",
                    spec.stock()
            ));
            index++;
        }
        Map<String, SkuDraft> byKey = new LinkedHashMap<>();
        for (SkuDraft spec : specs) {
            if (byKey.put(spec.key(), spec) != null) {
                throw new ApiException(400, "规格标识重复");
            }
        }
        specs.sort(Comparator.comparingInt(spec -> depthOf(spec, byKey, 0)));
        Set<String> parentKeys = new HashSet<>();
        for (SkuDraft spec : specs) {
            if (spec.parentKey() != null && !spec.parentKey().isBlank()) {
                parentKeys.add(spec.parentKey());
            }
        }
        Map<String, String> siblings = new LinkedHashMap<>();
        List<SkuDraft> ready = new ArrayList<>();
        int row = 1;
        for (SkuDraft spec : specs) {
            String name = required(spec.skuName(), "规格第 " + row + " 行请填写规格名称");
            String parent = spec.parentKey() == null ? "" : spec.parentKey();
            if (!parent.isBlank() && !byKey.containsKey(parent)) {
                throw new ApiException(400, "规格“" + name + "”的父规格不存在");
            }
            if (parent.equals(spec.key())) {
                throw new ApiException(400, "规格不能挂在自身下面：" + name);
            }
            if (!parent.isBlank()) {
                String grandParent = byKey.get(parent).parentKey();
                if (grandParent != null && !grandParent.isBlank()) {
                    throw new ApiException(400, "规格最多两级");
                }
            }
            if (siblings.put(parent + "|" + name, name) != null) {
                throw new ApiException(400, "同一父规格下不能重复：" + name);
            }
            String image = keepImages ? spec.imageUrl() : "";
            if (parentKeys.contains(spec.key())) {
                ready.add(new SkuDraft(spec.key(), parent, name, null, null, null, null, image, null));
                row++;
                continue;
            }
            String unit = spec.unitName() == null ? "" : spec.unitName().trim();
            if (unit.isBlank()) {
                throw new ApiException(400, "规格第 " + row + " 行请选择单位");
            }
            BigDecimal price = moneyValue(spec.salePrice(), "规格第 " + row + " 行的售价必须大于 0");
            if (price.signum() <= 0) {
                throw new ApiException(400, "规格第 " + row + " 行的售价必须大于 0");
            }
            BigDecimal market = blankMoney(spec.marketPrice());
            if (market != null && market.compareTo(price) < 0) {
                throw new ApiException(400, "规格第 " + row + " 行的划线价应大于或等于售价");
            }
            BigDecimal member = blankMoney(spec.memberPrice());
            if (member != null && (member.signum() <= 0 || member.compareTo(price) > 0)) {
                throw new ApiException(400, "规格第 " + row + " 行的会员价应大于 0 且不高于售价");
            }
            int stock = spec.stock() == null ? -1 : spec.stock();
            if (stock < 0) {
                throw new ApiException(400, "规格第 " + row + " 行的库存需为非负整数");
            }
            ready.add(new SkuDraft(spec.key(), parent, name, unit, price, market, member, image, stock));
            row++;
        }
        return ready;
    }

    private int depthOf(SkuDraft spec, Map<String, SkuDraft> byKey, int guard) {
        if (guard > 1) {
            throw new ApiException(400, "规格最多两级");
        }
        String parent = spec.parentKey();
        if (parent == null || parent.isBlank() || !byKey.containsKey(parent)) {
            return 0;
        }
        return depthOf(byKey.get(parent), byKey, guard + 1) + 1;
    }

    private String servicesHtml(String raw) {
        JsonNode node = readJson(raw);
        if (node != null && node.has("html")) {
            return node.get("html").asText("");
        }
        return "";
    }

    private boolean specImages(String raw) {
        JsonNode node = readJson(raw);
        if (node != null && node.has("specImages")) {
            return node.get("specImages").asBoolean(true);
        }
        return true;
    }

    private String serviceJson(String html, boolean specImages) {
        ObjectNode node = json.createObjectNode();
        node.put("html", html == null ? "" : html);
        node.put("specImages", specImages);
        return node.toString();
    }

    private JsonNode readJson(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return json.readTree(raw);
        } catch (Exception exception) {
            return null;
        }
    }

    private static String sanitizeHtml(String value) {
        if (value == null) {
            return "";
        }
        return value
                .replaceAll("(?i)<script[\\s\\S]*?>[\\s\\S]*?</script>", "")
                .replaceAll("(?i)<iframe[\\s\\S]*?>[\\s\\S]*?</iframe>", "")
                .replaceAll("(?i)\\son\\w+\\s*=\\s*(\"[^\"]*\"|'[^']*'|[^\\s>]+)", "")
                .replaceAll("(?i)javascript:", "");
    }

    private static BigDecimal blankMoney(BigDecimal value) {
        if (value == null || value.signum() == 0) {
            return null;
        }
        return value.setScale(2, RoundingMode.HALF_UP);
    }

    public record ProductDraft(
            String productName,
            String spuNo,
            Long categoryId,
            Integer fakeSold,
            String detail,
            String services,
            Boolean specImages,
            List<String> imageUrls,
            List<SkuDraft> skus
    ) {
    }

    public record SkuDraft(
            String key,
            String parentKey,
            String skuName,
            String unitName,
            BigDecimal salePrice,
            BigDecimal marketPrice,
            BigDecimal memberPrice,
            String imageUrl,
            Integer stock
    ) {
    }
}
