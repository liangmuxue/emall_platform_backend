package com.emall.web;

import java.util.List;
import java.util.Map;

import com.emall.api.ApiResponse;
import com.emall.auth.Session;
import com.emall.trade.AfterSaleService;
import com.emall.trade.AfterSaleService.ApplyRequest;
import com.emall.trade.AfterSaleService.ReplyRequest;
import com.emall.trade.AfterSaleService.VerdictRequest;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class AfterSaleController {

    private final AfterSaleService afterSale;

    public AfterSaleController(AfterSaleService afterSale) {
        this.afterSale = afterSale;
    }

    @GetMapping("/api/c/orders/{id}/after-sales")
    public ApiResponse<Map<String, Object>> board(HttpServletRequest request, @PathVariable long id) {
        return ApiResponse.ok(afterSale.memberBoard(account(request), id));
    }

    @PostMapping("/api/c/orders/{id}/after-sales/quote")
    public ApiResponse<Map<String, Object>> quote(
            HttpServletRequest request,
            @PathVariable long id,
            @RequestBody ApplyRequest body
    ) {
        return ApiResponse.ok(afterSale.quote(account(request), id, body));
    }

    @PostMapping("/api/c/orders/{id}/after-sales")
    public ApiResponse<Map<String, Object>> apply(
            HttpServletRequest request,
            @PathVariable long id,
            @RequestBody ApplyRequest body
    ) {
        return ApiResponse.ok(afterSale.apply(account(request), id, body));
    }

    @PostMapping("/api/c/after-sales/{id}/tracking")
    public ApiResponse<Map<String, Object>> tracking(
            HttpServletRequest request,
            @PathVariable long id,
            @RequestBody TrackingBody body
    ) {
        return ApiResponse.ok(afterSale.tracking(account(request), id, body == null ? null : body.trackingNo()));
    }

    @PostMapping("/api/c/after-sales/{id}/arbitrate")
    public ApiResponse<Map<String, Object>> arbitrate(
            HttpServletRequest request,
            @PathVariable long id,
            @RequestBody ReasonBody body
    ) {
        return ApiResponse.ok(afterSale.arbitrate(account(request), id, body == null ? null : body.reason()));
    }

    @PostMapping("/api/c/after-sales/{id}/cancel")
    public ApiResponse<Map<String, Object>> cancel(HttpServletRequest request, @PathVariable long id) {
        return ApiResponse.ok(afterSale.cancel(account(request), id));
    }

    @GetMapping("/api/merchant/after-sales")
    public ApiResponse<List<Map<String, Object>>> merchantList(HttpServletRequest request) {
        return ApiResponse.ok(afterSale.merchantList(account(request)));
    }

    @GetMapping("/api/merchant/after-sales/{id}")
    public ApiResponse<Map<String, Object>> merchantDetail(HttpServletRequest request, @PathVariable long id) {
        return ApiResponse.ok(afterSale.merchantDetail(account(request), id));
    }

    @PostMapping("/api/merchant/after-sales/{id}/agree")
    public ApiResponse<Map<String, Object>> agree(
            HttpServletRequest request,
            @PathVariable long id,
            @RequestBody(required = false) ReplyRequest body
    ) {
        return ApiResponse.ok(afterSale.agree(account(request), id, body == null ? null : body.reply()));
    }

    @PostMapping("/api/merchant/after-sales/{id}/reject")
    public ApiResponse<Map<String, Object>> reject(
            HttpServletRequest request,
            @PathVariable long id,
            @RequestBody ReplyRequest body
    ) {
        return ApiResponse.ok(afterSale.reject(account(request), id, body == null ? null : body.reply()));
    }

    @PostMapping("/api/merchant/after-sales/{id}/receive")
    public ApiResponse<Map<String, Object>> receive(HttpServletRequest request, @PathVariable long id) {
        return ApiResponse.ok(afterSale.receiveReturn(account(request), id));
    }

    @GetMapping("/api/platform/after-sales")
    public ApiResponse<List<Map<String, Object>>> platformList() {
        return ApiResponse.ok(afterSale.platformList());
    }

    @GetMapping("/api/platform/after-sales/{id}")
    public ApiResponse<Map<String, Object>> platformDetail(@PathVariable long id) {
        return ApiResponse.ok(afterSale.platformDetail(id));
    }

    @PostMapping("/api/platform/after-sales/{id}/arbitrate")
    public ApiResponse<Map<String, Object>> verdict(
            HttpServletRequest request,
            @PathVariable long id,
            @RequestBody VerdictRequest body
    ) {
        return ApiResponse.ok(afterSale.verdict(
                account(request),
                id,
                body == null ? null : body.support(),
                body == null ? null : body.result()
        ));
    }

    private static long account(HttpServletRequest request) {
        return ((Session) request.getAttribute(Session.ATTR)).accountId();
    }

    public record TrackingBody(String trackingNo) {
    }

    public record ReasonBody(String reason) {
    }
}
