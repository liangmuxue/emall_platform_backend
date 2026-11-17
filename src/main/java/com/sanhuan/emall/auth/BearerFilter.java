package com.sanhuan.emall.auth;

import java.io.IOException;
import java.util.Set;

import com.sanhuan.emall.api.ApiException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class BearerFilter extends OncePerRequestFilter {

    private static final Set<String> CLIENTS = Set.of("c", "mer", "pickup", "platform", "merchant");

    private final JwtService jwtService;
    private final TokenStore tokenStore;

    public BearerFilter(JwtService jwtService, TokenStore tokenStore) {
        this.jwtService = jwtService;
        this.tokenStore = tokenStore;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        if (HttpMethod.OPTIONS.matches(request.getMethod())) {
            filterChain.doFilter(request, response);
            return;
        }
        String path = request.getRequestURI();
        String clientPath = clientPath(path);
        if (clientPath == null) {
            filterChain.doFilter(request, response);
            return;
        }
        if (isPublic(request.getMethod(), path, clientPath)) {
            filterChain.doFilter(request, response);
            return;
        }
        String header = request.getHeader("Authorization");
        if (header == null || !header.startsWith("Bearer ")) {
            write(response, 401, "请先登录");
            return;
        }
        try {
            Session session = jwtService.parse(header.substring(7).trim());
            if (!session.client().path().equals(clientPath)) {
                write(response, 403, "这个登录态不能调用这个入口");
                return;
            }
            tokenStore.requireActive(session);
            request.setAttribute(Session.ATTR, session);
            filterChain.doFilter(request, response);
        } catch (ApiException exception) {
            write(response, exception.status(), exception.getMessage());
        }
    }

    private static boolean isPublic(String method, String path, String clientPath) {
        String prefix = "/api/" + clientPath;
        if (HttpMethod.GET.matches(method) && (prefix + "/health").equals(path)) {
            return true;
        }
        return HttpMethod.POST.matches(method) && (prefix + "/auth/login").equals(path);
    }

    private static String clientPath(String path) {
        if (path == null || !path.startsWith("/api/")) {
            return null;
        }
        String rest = path.substring(5);
        int slash = rest.indexOf('/');
        String client = slash < 0 ? rest : rest.substring(0, slash);
        return CLIENTS.contains(client) ? client : null;
    }

    private static void write(HttpServletResponse response, int status, String message) throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        String escaped = message.replace("\\", "\\\\").replace("\"", "\\\"");
        response.getWriter().write("{\"code\":-1,\"data\":null,\"message\":\"" + escaped + "\"}");
    }
}
