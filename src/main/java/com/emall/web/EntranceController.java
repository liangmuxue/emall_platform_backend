package com.emall.web;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.emall.api.ApiException;
import com.emall.api.ApiResponse;
import com.emall.api.ClientKind;
import com.emall.auth.AccessService;
import com.emall.auth.AuthService;
import com.emall.auth.Session;
import com.emall.auth.TokenStore;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/{client}")
public class EntranceController {

    private final AuthService authService;
    private final AccessService access;
    private final TokenStore tokenStore;
    private final JdbcTemplate jdbc;

    public EntranceController(AuthService authService, AccessService access, TokenStore tokenStore, JdbcTemplate jdbc) {
        this.authService = authService;
        this.access = access;
        this.tokenStore = tokenStore;
        this.jdbc = jdbc;
    }

    @GetMapping("/health")
    public ApiResponse<Map<String, String>> health(@PathVariable String client) {
        require(client);
        Map<String, String> body = new LinkedHashMap<>();
        body.put("client", client);
        body.put("mysql", mysql());
        body.put("redis", tokenStore.ping().isBlank() ? "down" : "ok");
        return ApiResponse.ok(body);
    }

    @PostMapping("/auth/login")
    public ApiResponse<Map<String, String>> login(
            @PathVariable String client,
            @RequestBody(required = false) LoginBody body
    ) {
        ClientKind kind = require(client);
        LoginBody form = body == null ? new LoginBody(null, null, null) : body;
        return ApiResponse.ok(authService.login(kind, form.username(), form.password(), form.mobile()));
    }

    @PostMapping("/auth/logout")
    public ApiResponse<Void> logout(@PathVariable String client, HttpServletRequest request) {
        require(client);
        authService.logout(session(request));
        return ApiResponse.ok(null);
    }

    @GetMapping("/auth/codes")
    public ApiResponse<List<String>> codes(@PathVariable String client, HttpServletRequest request) {
        require(client);
        return ApiResponse.ok(access.codes(session(request)));
    }

    @GetMapping("/user/info")
    public ApiResponse<Map<String, Object>> userInfo(@PathVariable String client, HttpServletRequest request) {
        require(client);
        String header = request.getHeader("Authorization");
        String token = header == null ? "" : header.replaceFirst("(?i)^Bearer\\s+", "");
        return ApiResponse.ok(authService.userInfo(session(request), token));
    }

    private ClientKind require(String client) {
        ClientKind kind = ClientKind.from(client);
        if (kind == null) {
            throw new ApiException(404, "未知入口");
        }
        return kind;
    }

    private static Session session(HttpServletRequest request) {
        Object value = request.getAttribute(Session.ATTR);
        if (value instanceof Session session) {
            return session;
        }
        throw new ApiException(401, "请先登录");
    }

    private String mysql() {
        try {
            Integer one = jdbc.queryForObject("SELECT 1", Integer.class);
            return one != null && one == 1 ? "ok" : "down";
        } catch (RuntimeException exception) {
            return "down";
        }
    }

    public record LoginBody(String username, String password, String mobile) {
    }
}
