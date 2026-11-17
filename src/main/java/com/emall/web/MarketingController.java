package com.emall.web;

import java.util.List;
import java.util.Map;

import com.emall.api.ApiResponse;
import com.emall.auth.Session;
import com.emall.trade.MarketingService;
import com.emall.trade.MarketingService.ActivityRequest;
import com.emall.trade.MarketingService.AuditRequest;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class MarketingController {

    private final MarketingService marketing;

    public MarketingController(MarketingService marketing) {
        this.marketing = marketing;
    }

    @GetMapping({"/api/merchant/activities", "/api/mer/activities"})
    public ApiResponse<List<Map<String, Object>>> merchantActivities(HttpServletRequest request) {
        return ApiResponse.ok(marketing.merchantActivities(account(request)));
    }

    @GetMapping({"/api/merchant/activities/options", "/api/mer/activities/options"})
    public ApiResponse<Map<String, Object>> options(HttpServletRequest request) {
        return ApiResponse.ok(marketing.options(account(request)));
    }

    @GetMapping({"/api/merchant/activities/{id}", "/api/mer/activities/{id}"})
    public ApiResponse<Map<String, Object>> merchantActivity(HttpServletRequest request, @PathVariable long id) {
        return ApiResponse.ok(marketing.merchantActivity(account(request), id));
    }

    @PostMapping({"/api/merchant/activities", "/api/mer/activities"})
    public ApiResponse<Map<String, Object>> create(HttpServletRequest request, @RequestBody ActivityRequest body) {
        return ApiResponse.ok(marketing.save(account(request), null, body));
    }

    @PutMapping({"/api/merchant/activities/{id}", "/api/mer/activities/{id}"})
    public ApiResponse<Map<String, Object>> update(HttpServletRequest request, @PathVariable long id, @RequestBody ActivityRequest body) {
        return ApiResponse.ok(marketing.save(account(request), id, body));
    }

    @PostMapping({"/api/merchant/activities/{id}/submit", "/api/mer/activities/{id}/submit"})
    public ApiResponse<Map<String, Object>> submit(HttpServletRequest request, @PathVariable long id) {
        return ApiResponse.ok(marketing.submit(account(request), id));
    }

    @PostMapping({"/api/merchant/activities/{id}/end", "/api/mer/activities/{id}/end"})
    public ApiResponse<Map<String, Object>> end(HttpServletRequest request, @PathVariable long id) {
        return ApiResponse.ok(marketing.end(account(request), id));
    }

    @GetMapping("/api/platform/activities")
    public ApiResponse<List<Map<String, Object>>> platformActivities() {
        return ApiResponse.ok(marketing.platformActivities());
    }

    @GetMapping("/api/platform/activities/{id}")
    public ApiResponse<Map<String, Object>> platformActivity(@PathVariable long id) {
        return ApiResponse.ok(marketing.platformActivity(id));
    }

    @PostMapping("/api/platform/activities/{id}/audit")
    public ApiResponse<Map<String, Object>> audit(HttpServletRequest request, @PathVariable long id, @RequestBody AuditRequest body) {
        return ApiResponse.ok(marketing.audit(account(request), id, body != null && Boolean.TRUE.equals(body.pass()), body == null ? null : body.remark()));
    }

    private static long account(HttpServletRequest request) {
        return ((Session) request.getAttribute(Session.ATTR)).accountId();
    }
}
