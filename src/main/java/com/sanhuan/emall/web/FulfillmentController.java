package com.sanhuan.emall.web;

import java.util.List;
import java.util.Map;

import com.sanhuan.emall.api.ApiResponse;
import com.sanhuan.emall.auth.Session;
import com.sanhuan.emall.trade.FulfillmentService;
import com.sanhuan.emall.trade.FulfillmentService.CodeBody;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class FulfillmentController {

    private final FulfillmentService fulfillment;

    public FulfillmentController(FulfillmentService fulfillment) {
        this.fulfillment = fulfillment;
    }

    @GetMapping({"/api/merchant/orders", "/api/mer/orders"})
    public ApiResponse<List<Map<String, Object>>> merchantOrders(HttpServletRequest request) {
        return ApiResponse.ok(fulfillment.merchantOrders(account(request)));
    }

    @GetMapping({"/api/merchant/orders/{id}", "/api/mer/orders/{id}"})
    public ApiResponse<Map<String, Object>> merchantOrder(HttpServletRequest request, @PathVariable long id) {
        return ApiResponse.ok(fulfillment.merchantOrder(account(request), id));
    }

    @PostMapping({"/api/merchant/orders/{id}/prepare", "/api/mer/orders/{id}/prepare"})
    public ApiResponse<Map<String, Object>> prepare(HttpServletRequest request, @PathVariable long id) {
        return ApiResponse.ok(fulfillment.prepare(account(request), id));
    }

    @PostMapping({"/api/merchant/orders/{id}/ready", "/api/mer/orders/{id}/ready"})
    public ApiResponse<Map<String, Object>> ready(HttpServletRequest request, @PathVariable long id) {
        return ApiResponse.ok(fulfillment.ready(account(request), id));
    }

    @GetMapping("/api/pickup/orders")
    public ApiResponse<Map<String, Object>> pickupBoard(HttpServletRequest request) {
        return ApiResponse.ok(fulfillment.pickupBoard(account(request)));
    }

    @GetMapping("/api/pickup/orders/{id}")
    public ApiResponse<Map<String, Object>> pickupOrder(HttpServletRequest request, @PathVariable long id) {
        return ApiResponse.ok(fulfillment.pickupOrder(account(request), id));
    }

    @PostMapping("/api/pickup/orders/lookup")
    public ApiResponse<Map<String, Object>> lookup(HttpServletRequest request, @RequestBody CodeBody body) {
        return ApiResponse.ok(fulfillment.lookup(account(request), body == null ? null : body.code()));
    }

    @PostMapping("/api/pickup/orders/{id}/confirm")
    public ApiResponse<Map<String, Object>> confirm(
            HttpServletRequest request,
            @PathVariable long id,
            @RequestBody CodeBody body
    ) {
        return ApiResponse.ok(fulfillment.confirmPickup(account(request), id, body == null ? null : body.code()));
    }

    @PostMapping("/api/c/orders/{id}/receive")
    public ApiResponse<Map<String, Object>> receive(HttpServletRequest request, @PathVariable long id) {
        return ApiResponse.ok(fulfillment.receive(account(request), id));
    }

    private static long account(HttpServletRequest request) {
        return ((Session) request.getAttribute(Session.ATTR)).accountId();
    }
}
