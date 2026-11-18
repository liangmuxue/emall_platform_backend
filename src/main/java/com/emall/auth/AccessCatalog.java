package com.emall.auth;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class AccessCatalog {

    public static final List<String> SCOPES = List.of("全部业务线", "仅线上商城", "仅会有便利", "仅鲜生活");

    private static final List<Menu> PLATFORM = List.of(
            leaf("overview", "经营概览"),
            group("user", "用户体系", List.of(
                    leaf("org", "平台组织"),
                    leaf("members", "C端会员"),
                    leaf("accounts", "商家账号")
            )),
            group("merchant", "商家门店", List.of(
                    leaf("entry", "入驻审核"),
                    leaf("shops", "店铺档案"),
                    leaf("pickups", "自提点")
            )),
            group("product", "商品中心", List.of(
                    leaf("categories", "商品类目"),
                    leaf("audits", "商品审核"),
                    leaf("catalog", "品牌规格")
            )),
            group("aftersale", "售后监管", List.of(leaf("aftersales", "售后仲裁"))),
            group("marketing", "营销中心", List.of(
                    leaf("activityTypes", "活动分类"),
                    leaf("campaigns", "活动审核")
            )),
            group("ops", "基础运营", List.of(leaf("rules", "内容协议"))),
            group("member", "会员运营", List.of(leaf("assets", "会员资产"))),
            group("finance", "财务数据", List.of(
                    leaf("finance", "财务对账"),
                    leaf("stats", "数据统计")
            )),
            group("service", "服务系统", List.of(
                    leaf("service", "客服工单"),
                    leaf("system", "系统风控")
            ))
    );

    private static final List<Menu> MERCHANT = List.of(
            leaf("dashboard", "店铺概览"),
            group("product", "商品管理", List.of(
                    leaf("products", "商品与库存"),
                    leaf("categories", "平台类目")
            )),
            group("order", "订单履约", List.of(
                    leaf("orders", "订单处理"),
                    leaf("aftersale", "退款售后")
            )),
            group("marketing", "店铺运营", List.of(leaf("marketing", "营销活动"))),
            group("biz", "经营与结算", List.of(
                    leaf("data", "经营数据"),
                    leaf("settlement", "结算中心")
            )),
            group("setting", "设置", List.of(
                    leaf("store", "店铺设置"),
                    leaf("roles", "岗位与权限"),
                    leaf("staff-users", "员工账号")
            ))
    );

    private static final List<Prefix> PLATFORM_API = List.of(
            new Prefix("/org", "org"),
            new Prefix("/merchant-accounts", "accounts"),
            new Prefix("/categories", "categories"),
            new Prefix("/products", "audits"),
            new Prefix("/activities", "campaigns"),
            new Prefix("/after-sales", "aftersales"),
            new Prefix("/settlements", "finance")
    );

    private static final List<Prefix> MERCHANT_API = List.of(
            new Prefix("/org/roles", "roles"),
            new Prefix("/org/staff", "staff-users"),
            new Prefix("/store", "store"),
            new Prefix("/uploads", "products"),
            new Prefix("/products", "products"),
            new Prefix("/categories", "categories"),
            new Prefix("/orders", "orders"),
            new Prefix("/after-sales", "aftersale"),
            new Prefix("/activities", "marketing"),
            new Prefix("/settlements", "settlement")
    );

    private AccessCatalog() {
    }

    public static List<Map<String, Object>> tree(boolean platform) {
        return (platform ? PLATFORM : MERCHANT).stream().map(Menu::view).toList();
    }

    public static Set<String> leaves(boolean platform) {
        return Set.copyOf(leavesOf(platform ? PLATFORM : MERCHANT));
    }

    public static String requiredMenu(String client, String path) {
        if (path == null) {
            return null;
        }
        if ("platform".equals(client) && path.startsWith("/api/platform/")) {
            return match(path.substring("/api/platform".length()), PLATFORM_API);
        }
        if ("merchant".equals(client) && path.startsWith("/api/merchant/")) {
            return match(path.substring("/api/merchant".length()), MERCHANT_API);
        }
        if ("mer".equals(client) && path.startsWith("/api/mer/")) {
            return match(path.substring("/api/mer".length()), MERCHANT_API);
        }
        return null;
    }

    public static String home(boolean platform, List<String> menus) {
        List<String> order = platform
                ? List.of("overview", "org", "accounts", "categories", "audits", "aftersales", "campaigns", "finance")
                : List.of("dashboard", "products", "categories", "orders", "aftersale", "marketing", "settlement", "store", "roles", "staff-users");
        Map<String, String> paths = platform
                ? Map.of(
                "overview", "/dashboard/analytics",
                "org", "/org",
                "accounts", "/merchant-accounts",
                "categories", "/catalog/categories",
                "audits", "/catalog/audits",
                "aftersales", "/orders/after-sales",
                "campaigns", "/marketing/audits",
                "finance", "/finance/settlements"
        )
                : Map.of(
                "dashboard", "/dashboard/analytics",
                "products", "/goods/products",
                "categories", "/goods/categories",
                "orders", "/orders/fulfillment",
                "aftersale", "/orders/after-sales",
                "marketing", "/marketing/activities",
                "settlement", "/finance/settlements",
                "store", "/settings/store",
                "roles", "/settings/roles",
                "staff-users", "/settings/staff"
        );
        for (String menu : order) {
            if (menus.contains(menu)) {
                return paths.get(menu);
            }
        }
        return platform ? "/dashboard/analytics" : "/dashboard/analytics";
    }

    private static String match(String rest, List<Prefix> prefixes) {
        if (rest.startsWith("/auth") || rest.startsWith("/user") || "/health".equals(rest)) {
            return null;
        }
        for (Prefix prefix : prefixes) {
            if (rest.equals(prefix.path()) || rest.startsWith(prefix.path() + "/")) {
                return prefix.menu();
            }
        }
        return null;
    }

    private static List<String> leavesOf(List<Menu> nodes) {
        List<String> ids = new ArrayList<>();
        for (Menu node : nodes) {
            if (node.children().isEmpty()) {
                ids.add(node.id());
            } else {
                ids.addAll(leavesOf(node.children()));
            }
        }
        return ids;
    }

    private static Menu leaf(String id, String label) {
        return new Menu(id, label, List.of());
    }

    private static Menu group(String id, String label, List<Menu> children) {
        return new Menu(id, label, children);
    }

    public record Menu(String id, String label, List<Menu> children) {
        Map<String, Object> view() {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", id);
            item.put("label", label);
            if (!children.isEmpty()) {
                item.put("children", children.stream().map(Menu::view).toList());
            }
            return item;
        }
    }

    private record Prefix(String path, String menu) {
    }
}
