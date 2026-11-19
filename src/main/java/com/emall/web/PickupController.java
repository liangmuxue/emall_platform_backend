package com.emall.web;

import java.util.List;
import java.util.Map;

import com.emall.api.ApiResponse;
import com.emall.pickup.PickupAdminService;
import com.emall.pickup.PickupAdminService.AccountBody;
import com.emall.pickup.PickupAdminService.PointBody;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class PickupController {

    private final PickupAdminService pickups;

    public PickupController(PickupAdminService pickups) {
        this.pickups = pickups;
    }

    @GetMapping("/api/platform/pickup-points")
    public ApiResponse<List<Map<String, Object>>> points() {
        return ApiResponse.ok(pickups.points());
    }

    @PostMapping("/api/platform/pickup-points")
    public ApiResponse<Map<String, Object>> createPoint(@RequestBody PointBody body) {
        return ApiResponse.ok(pickups.savePoint(null, body));
    }

    @PutMapping("/api/platform/pickup-points/{id}")
    public ApiResponse<Map<String, Object>> updatePoint(@PathVariable long id, @RequestBody PointBody body) {
        return ApiResponse.ok(pickups.savePoint(id, body));
    }

    @GetMapping("/api/platform/pickup-points/accounts")
    public ApiResponse<List<Map<String, Object>>> accounts() {
        return ApiResponse.ok(pickups.accounts());
    }

    @PostMapping("/api/platform/pickup-points/accounts")
    public ApiResponse<Map<String, Object>> createAccount(@RequestBody AccountBody body) {
        return ApiResponse.ok(pickups.saveAccount(null, body));
    }

    @PutMapping("/api/platform/pickup-points/accounts/{id}")
    public ApiResponse<Map<String, Object>> updateAccount(@PathVariable long id, @RequestBody AccountBody body) {
        return ApiResponse.ok(pickups.saveAccount(id, body));
    }

    @PutMapping("/api/platform/pickup-points/accounts/{id}/password")
    public ApiResponse<Map<String, Object>> resetPassword(@PathVariable long id, @RequestBody PasswordBody body) {
        return ApiResponse.ok(pickups.resetPassword(id, body == null ? null : body.password()));
    }

    public record PasswordBody(String password) {
    }
}
