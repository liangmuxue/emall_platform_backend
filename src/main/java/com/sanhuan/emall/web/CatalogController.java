package com.sanhuan.emall.web;

import java.util.List;
import java.util.Map;

import com.sanhuan.emall.api.ApiResponse;
import com.sanhuan.emall.auth.Session;
import com.sanhuan.emall.catalog.CatalogService;
import com.sanhuan.emall.catalog.CatalogService.ProductDraft;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class CatalogController {

    private final CatalogService catalog;

    public CatalogController(CatalogService catalog) {
        this.catalog = catalog;
    }

    @GetMapping("/api/platform/categories")
    public ApiResponse<List<Map<String, Object>>> platformCategories() {
        return ApiResponse.ok(catalog.categories(false));
    }

    @PostMapping("/api/platform/categories")
    public ApiResponse<Map<String, Object>> createCategory(@RequestBody CategoryBody body) {
        return ApiResponse.ok(catalog.saveCategory(null, body.name(), body.parentId(), body.sortNo(), body.status()));
    }

    @PutMapping("/api/platform/categories/{id}")
    public ApiResponse<Map<String, Object>> updateCategory(@PathVariable long id, @RequestBody CategoryBody body) {
        return ApiResponse.ok(catalog.saveCategory(id, body.name(), body.parentId(), body.sortNo(), body.status()));
    }

    @DeleteMapping("/api/platform/categories/{id}")
    public ApiResponse<Void> deleteCategory(@PathVariable long id) {
        catalog.deleteCategory(id);
        return ApiResponse.ok(null);
    }

    @GetMapping("/api/platform/products")
    public ApiResponse<List<Map<String, Object>>> platformProducts(@RequestParam(required = false) String status) {
        return ApiResponse.ok(catalog.products(null, status));
    }

    @GetMapping("/api/platform/products/{id}")
    public ApiResponse<Map<String, Object>> platformProduct(@PathVariable long id) {
        return ApiResponse.ok(catalog.product(id, null));
    }

    @PostMapping("/api/platform/products/{id}/audit")
    public ApiResponse<Void> audit(HttpServletRequest request, @PathVariable long id, @RequestBody AuditBody body) {
        catalog.audit(session(request), id, body.result(), body.remark());
        return ApiResponse.ok(null);
    }

    @GetMapping("/api/merchant/shop")
    public ApiResponse<Map<String, Object>> merchantShop(HttpServletRequest request) {
        return ApiResponse.ok(catalog.shopProfile(session(request).accountId()));
    }

    @GetMapping("/api/merchant/categories")
    public ApiResponse<List<Map<String, Object>>> merchantCategories() {
        return ApiResponse.ok(catalog.categories(false));
    }

    @GetMapping("/api/merchant/products")
    public ApiResponse<List<Map<String, Object>>> merchantProducts(HttpServletRequest request) {
        long shopId = catalog.merchantShopId(session(request).accountId());
        return ApiResponse.ok(catalog.products(shopId, null));
    }

    @GetMapping("/api/merchant/products/{id}")
    public ApiResponse<Map<String, Object>> merchantProduct(HttpServletRequest request, @PathVariable long id) {
        long shopId = catalog.merchantShopId(session(request).accountId());
        return ApiResponse.ok(catalog.product(id, shopId));
    }

    @PostMapping("/api/merchant/products")
    public ApiResponse<Map<String, Object>> createProduct(HttpServletRequest request, @RequestBody ProductDraft body) {
        return ApiResponse.ok(catalog.saveProduct(session(request), null, body));
    }

    @PutMapping("/api/merchant/products/{id}")
    public ApiResponse<Map<String, Object>> updateProduct(
            HttpServletRequest request,
            @PathVariable long id,
            @RequestBody ProductDraft body
    ) {
        return ApiResponse.ok(catalog.saveProduct(session(request), id, body));
    }

    @PostMapping("/api/merchant/products/{id}/submit")
    public ApiResponse<Void> submit(HttpServletRequest request, @PathVariable long id) {
        catalog.submit(session(request), id);
        return ApiResponse.ok(null);
    }

    @GetMapping("/api/c/categories")
    public ApiResponse<List<Map<String, Object>>> consumerCategories() {
        return ApiResponse.ok(catalog.categories(true));
    }

    @GetMapping("/api/c/products")
    public ApiResponse<List<Map<String, Object>>> consumerProducts(
            @RequestParam(required = false) Long categoryId,
            @RequestParam(required = false) String keyword
    ) {
        return ApiResponse.ok(catalog.shelf(categoryId, keyword));
    }

    @GetMapping("/api/c/products/{id}")
    public ApiResponse<Map<String, Object>> consumerProduct(HttpServletRequest request, @PathVariable long id) {
        return ApiResponse.ok(catalog.consumerProduct(id, session(request).accountId()));
    }

    @PostMapping("/api/c/products/{id}/favorite")
    public ApiResponse<Map<String, Object>> favorite(HttpServletRequest request, @PathVariable long id) {
        return ApiResponse.ok(catalog.toggleFavorite(session(request).accountId(), id));
    }

    private static Session session(HttpServletRequest request) {
        return (Session) request.getAttribute(Session.ATTR);
    }

    public record CategoryBody(String name, Long parentId, Integer sortNo, String status) {
    }

    public record AuditBody(String result, String remark) {
    }
}
