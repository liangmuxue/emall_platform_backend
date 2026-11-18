package com.emall.auth;

import java.io.IOException;

import com.emall.api.ApiException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Order(1)
public class AccessFilter extends OncePerRequestFilter {

    private final AccessService access;

    public AccessFilter(AccessService access) {
        this.access = access;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        String menu = AccessCatalog.requiredMenu(client(request.getRequestURI()), request.getRequestURI());
        if (menu == null) {
            filterChain.doFilter(request, response);
            return;
        }
        Object value = request.getAttribute(Session.ATTR);
        if (!(value instanceof Session session)) {
            filterChain.doFilter(request, response);
            return;
        }
        try {
            access.require(session, menu);
            filterChain.doFilter(request, response);
        } catch (ApiException exception) {
            response.setStatus(exception.status());
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setCharacterEncoding("UTF-8");
            String message = exception.getMessage().replace("\\", "\\\\").replace("\"", "\\\"");
            response.getWriter().write("{\"code\":-1,\"data\":null,\"message\":\"" + message + "\"}");
        }
    }

    private static String client(String path) {
        if (path == null || !path.startsWith("/api/")) {
            return "";
        }
        String rest = path.substring(5);
        int slash = rest.indexOf('/');
        return slash < 0 ? rest : rest.substring(0, slash);
    }
}
