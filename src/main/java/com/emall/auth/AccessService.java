package com.emall.auth;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import com.emall.api.ApiException;
import com.emall.api.ClientKind;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class AccessService {

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public AccessService(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public List<String> codes(Session session) {
        if (session.client() != ClientKind.PLATFORM && session.client() != ClientKind.MERCHANT && session.client() != ClientKind.MER) {
            return List.of("*");
        }
        return menus(session.client() == ClientKind.PLATFORM, session.accountId());
    }

    public void require(Session session, String menu) {
        requireAny(session, menu == null ? List.of() : List.of(menu));
    }

    public void requireAny(Session session, List<String> menus) {
        if (menus == null || menus.isEmpty()) {
            return;
        }
        List<String> owned = codes(session);
        for (String menu : menus) {
            if (owned.contains(menu)) {
                return;
            }
        }
        throw new ApiException(403, "无权访问该功能，请联系管理员分配岗位权限");
    }

    public List<String> menus(boolean platform, long accountId) {
        String sql = platform ? """
                SELECT r.permission_json, r.status
                FROM platform_account_role ar
                JOIN platform_role r ON r.id = ar.role_id
                WHERE ar.platform_account_id = ?
                """ : """
                SELECT r.permission_json, r.status
                FROM merchant_account_role ar
                JOIN merchant_role r ON r.id = ar.role_id
                WHERE ar.merchant_account_id = ?
                """;
        List<JsonNode> roles = jdbc.query(sql, (rs, row) -> {
            if (!"ENABLED".equals(rs.getString("status"))) {
                return null;
            }
            return read(rs.getString("permission_json"));
        }, accountId);
        Set<String> menus = new LinkedHashSet<>();
        boolean all = false;
        for (JsonNode role : roles) {
            if (role == null) {
                continue;
            }
            JsonNode listed = role.isArray() ? role : role.path("menus");
            for (JsonNode menu : listed) {
                if ("*".equals(menu.asText())) {
                    all = true;
                } else if ("org".equals(menu.asText())) {
                    menus.add("org-roles");
                    menus.add("org-staff");
                } else if (!menu.asText().isBlank()) {
                    menus.add(menu.asText());
                }
            }
        }
        if (all) {
            menus.addAll(AccessCatalog.leaves(platform));
        }
        return new ArrayList<>(menus);
    }

    public JsonNode read(String text) {
        try {
            return json.readTree(text == null || text.isBlank() ? "{}" : text);
        } catch (Exception exception) {
            return json.createObjectNode();
        }
    }
}
