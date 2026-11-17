package com.sanhuan.emall.web;

import java.util.List;
import java.util.Map;

import com.sanhuan.emall.api.ApiResponse;
import com.sanhuan.emall.auth.Session;
import com.sanhuan.emall.trade.TradeService;
import com.sanhuan.emall.trade.TradeService.AddressRequest;
import com.sanhuan.emall.trade.TradeService.LineRequest;
import com.sanhuan.emall.trade.TradeService.OrderRequest;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class TradeController {

    private final TradeService trade;

    public TradeController(TradeService trade) {
        this.trade = trade;
    }

    @GetMapping("/api/c/cart")
    public ApiResponse<Map<String, Object>> cart(HttpServletRequest request) {
        return ApiResponse.ok(trade.cart(member(request)));
    }

    @PostMapping("/api/c/cart/items")
    public ApiResponse<Map<String, Object>> addItem(HttpServletRequest request, @RequestBody CartBody body) {
        return ApiResponse.ok(trade.addItem(member(request), body.skuId(), body.quantity()));
    }

    @PutMapping("/api/c/cart/items/{id}")
    public ApiResponse<Map<String, Object>> updateItem(
            HttpServletRequest request,
            @PathVariable long id,
            @RequestBody CartPatch body
    ) {
        return ApiResponse.ok(trade.updateItem(member(request), id, body.quantity(), body.selected()));
    }

    @PutMapping("/api/c/cart/selection")
    public ApiResponse<Map<String, Object>> selection(HttpServletRequest request, @RequestBody SelectionBody body) {
        return ApiResponse.ok(trade.selectAll(member(request), Boolean.TRUE.equals(body.selected())));
    }

    @PostMapping("/api/c/checkout/quote")
    public ApiResponse<Map<String, Object>> quote(HttpServletRequest request, @RequestBody QuoteBody body) {
        return ApiResponse.ok(trade.quote(member(request), body.usePoints(), body.couponId(), body.templateId(), body.lines()));
    }

    @GetMapping("/api/c/pickup-points")
    public ApiResponse<List<Map<String, Object>>> pickupPoints() {
        return ApiResponse.ok(trade.pickupPoints());
    }

    @GetMapping("/api/c/addresses")
    public ApiResponse<List<Map<String, Object>>> addresses(HttpServletRequest request) {
        return ApiResponse.ok(trade.addresses(member(request)));
    }

    @PostMapping("/api/c/addresses")
    public ApiResponse<Map<String, Object>> saveAddress(HttpServletRequest request, @RequestBody AddressRequest body) {
        return ApiResponse.ok(trade.saveAddress(member(request), body));
    }

    @PostMapping("/api/c/orders")
    public ApiResponse<Map<String, Object>> place(HttpServletRequest request, @RequestBody OrderRequest body) {
        return ApiResponse.ok(trade.place(session(request), body));
    }

    @GetMapping("/api/c/orders/{id}")
    public ApiResponse<Map<String, Object>> order(HttpServletRequest request, @PathVariable long id) {
        return ApiResponse.ok(trade.orderView(member(request), id));
    }

    @PostMapping("/api/c/orders/{id}/pay")
    public ApiResponse<Map<String, Object>> pay(HttpServletRequest request, @PathVariable long id) {
        return ApiResponse.ok(trade.pay(session(request), id));
    }

    @PostMapping("/api/c/orders/{id}/cancel")
    public ApiResponse<Map<String, Object>> cancel(HttpServletRequest request, @PathVariable long id) {
        return ApiResponse.ok(trade.cancel(session(request), id));
    }

    private static long member(HttpServletRequest request) {
        return session(request).accountId();
    }

    private static Session session(HttpServletRequest request) {
        return (Session) request.getAttribute(Session.ATTR);
    }

    public record CartBody(Long skuId, Integer quantity) {
    }

    public record CartPatch(Integer quantity, Boolean selected) {
    }

    public record SelectionBody(Boolean selected) {
    }

    public record QuoteBody(Boolean usePoints, Long couponId, Long templateId, List<LineRequest> lines) {
    }
}
