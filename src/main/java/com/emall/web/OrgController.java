package com.emall.web;

import java.util.List;
import java.util.Map;

import com.emall.api.ApiResponse;
import com.emall.auth.Session;
import com.emall.org.OrgService;
import com.emall.org.OrgService.AccountRequest;
import com.emall.org.OrgService.RoleRequest;
import com.emall.org.OrgService.StaffRequest;
import com.emall.org.OrgService.StoreRequest;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class OrgController {

    private final OrgService org;

    public OrgController(OrgService org) {
        this.org = org;
    }

    @GetMapping("/api/platform/org/menus")
    public ApiResponse<List<Map<String, Object>>> platformMenus() {
        return ApiResponse.ok(org.platformMenus());
    }

    @GetMapping("/api/platform/org/roles")
    public ApiResponse<List<Map<String, Object>>> platformRoles() {
        return ApiResponse.ok(org.platformRoles());
    }

    @PostMapping("/api/platform/org/roles")
    public ApiResponse<Map<String, Object>> createPlatformRole(@RequestBody RoleRequest body) {
        return ApiResponse.ok(org.savePlatformRole(null, body));
    }

    @PutMapping("/api/platform/org/roles/{id}")
    public ApiResponse<Map<String, Object>> updatePlatformRole(@PathVariable long id, @RequestBody RoleRequest body) {
        return ApiResponse.ok(org.savePlatformRole(id, body));
    }

    @GetMapping("/api/platform/org/staff")
    public ApiResponse<List<Map<String, Object>>> platformStaff() {
        return ApiResponse.ok(org.platformStaff());
    }

    @PostMapping("/api/platform/org/staff")
    public ApiResponse<Map<String, Object>> createPlatformStaff(HttpServletRequest request, @RequestBody StaffRequest body) {
        return ApiResponse.ok(org.savePlatformStaff(account(request), null, body));
    }

    @PutMapping("/api/platform/org/staff/{id}")
    public ApiResponse<Map<String, Object>> updatePlatformStaff(
            HttpServletRequest request,
            @PathVariable long id,
            @RequestBody StaffRequest body
    ) {
        return ApiResponse.ok(org.savePlatformStaff(account(request), id, body));
    }

    @GetMapping("/api/platform/merchant-accounts/roles")
    public ApiResponse<List<Map<String, Object>>> merchantRoleOptions() {
        return ApiResponse.ok(org.merchantRoles());
    }

    @GetMapping("/api/platform/merchant-accounts")
    public ApiResponse<List<Map<String, Object>>> accounts() {
        return ApiResponse.ok(org.merchantAccounts());
    }

    @PostMapping("/api/platform/merchant-accounts")
    public ApiResponse<Map<String, Object>> openAccount(@RequestBody AccountRequest body) {
        return ApiResponse.ok(org.openMerchantAccount(body));
    }

    @PutMapping("/api/platform/merchant-accounts/{id}")
    public ApiResponse<Map<String, Object>> updateAccount(@PathVariable long id, @RequestBody AccountRequest body) {
        return ApiResponse.ok(org.updateMerchantAccount(id, body));
    }

    @GetMapping("/api/merchant/org/menus")
    public ApiResponse<List<Map<String, Object>>> merchantMenus() {
        return ApiResponse.ok(org.merchantMenus());
    }

    @GetMapping("/api/merchant/org/roles")
    public ApiResponse<List<Map<String, Object>>> merchantRoles() {
        return ApiResponse.ok(org.merchantRoles());
    }

    @PostMapping("/api/merchant/org/roles")
    public ApiResponse<Map<String, Object>> createMerchantRole(@RequestBody RoleRequest body) {
        return ApiResponse.ok(org.saveMerchantRole(null, body));
    }

    @PutMapping("/api/merchant/org/roles/{id}")
    public ApiResponse<Map<String, Object>> updateMerchantRole(@PathVariable long id, @RequestBody RoleRequest body) {
        return ApiResponse.ok(org.saveMerchantRole(id, body));
    }

    @GetMapping("/api/merchant/org/staff")
    public ApiResponse<List<Map<String, Object>>> merchantStaff(HttpServletRequest request) {
        return ApiResponse.ok(org.merchantStaff(account(request)));
    }

    @PostMapping("/api/merchant/org/staff")
    public ApiResponse<Map<String, Object>> createMerchantStaff(HttpServletRequest request, @RequestBody StaffRequest body) {
        return ApiResponse.ok(org.saveMerchantStaff(account(request), null, body));
    }

    @PutMapping("/api/merchant/org/staff/{id}")
    public ApiResponse<Map<String, Object>> updateMerchantStaff(
            HttpServletRequest request,
            @PathVariable long id,
            @RequestBody StaffRequest body
    ) {
        return ApiResponse.ok(org.saveMerchantStaff(account(request), id, body));
    }

    @GetMapping("/api/merchant/store")
    public ApiResponse<Map<String, Object>> store(HttpServletRequest request) {
        return ApiResponse.ok(org.store(account(request)));
    }

    @PutMapping("/api/merchant/store")
    public ApiResponse<Map<String, Object>> saveStore(HttpServletRequest request, @RequestBody StoreRequest body) {
        return ApiResponse.ok(org.saveStore(account(request), body));
    }

    private static long account(HttpServletRequest request) {
        return ((Session) request.getAttribute(Session.ATTR)).accountId();
    }
}
