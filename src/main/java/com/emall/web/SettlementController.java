package com.emall.web;

import java.util.List;
import java.util.Map;

import com.emall.api.ApiResponse;
import com.emall.auth.Session;
import com.emall.trade.SettlementService;
import com.emall.trade.SettlementService.AdjustmentRequest;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class SettlementController {

    private final SettlementService settlement;

    public SettlementController(SettlementService settlement) {
        this.settlement = settlement;
    }

    @GetMapping("/api/platform/settlements/current")
    public ApiResponse<List<Map<String, Object>>> platformCurrent() {
        return ApiResponse.ok(settlement.platformCurrent());
    }

    @GetMapping("/api/merchant/settlements/current")
    public ApiResponse<Map<String, Object>> merchantCurrent(HttpServletRequest request) {
        return ApiResponse.ok(settlement.merchantCurrent(account(request)));
    }

    @GetMapping("/api/platform/settlements")
    public ApiResponse<List<Map<String, Object>>> platformList() {
        return ApiResponse.ok(settlement.platformList());
    }

    @GetMapping("/api/platform/settlements/{id}")
    public ApiResponse<Map<String, Object>> platformDetail(@PathVariable long id) {
        return ApiResponse.ok(settlement.platformDetail(id));
    }

    @PostMapping("/api/platform/settlements/generate")
    public ApiResponse<Map<String, Object>> generate() {
        return ApiResponse.ok(settlement.generatePrevious());
    }

    @PostMapping("/api/platform/settlements/{id}/reconcile")
    public ApiResponse<Map<String, Object>> reconcile(
            HttpServletRequest request,
            @PathVariable long id,
            @RequestBody(required = false) AdjustmentRequest body
    ) {
        return ApiResponse.ok(settlement.reconcile(account(request), id, body == null ? null : body.adjustment()));
    }

    @PostMapping("/api/platform/settlements/{id}/pay")
    public ApiResponse<Map<String, Object>> pay(@PathVariable long id) {
        return ApiResponse.ok(settlement.pay(id));
    }

    @GetMapping("/api/merchant/settlements")
    public ApiResponse<List<Map<String, Object>>> merchantList(HttpServletRequest request) {
        return ApiResponse.ok(settlement.merchantList(account(request)));
    }

    @GetMapping("/api/merchant/settlements/{id}")
    public ApiResponse<Map<String, Object>> merchantDetail(HttpServletRequest request, @PathVariable long id) {
        return ApiResponse.ok(settlement.merchantDetail(account(request), id));
    }

    private static long account(HttpServletRequest request) {
        return ((Session) request.getAttribute(Session.ATTR)).accountId();
    }
}
