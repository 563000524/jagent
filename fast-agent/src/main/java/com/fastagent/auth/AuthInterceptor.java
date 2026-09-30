package com.fastagent.auth;

import com.fastagent.AppLog;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.servlet.HandlerInterceptor;

import java.nio.charset.StandardCharsets;

/**
 * 鉴权拦截器：除登录接口与健康检查外，所有 {@code /api/**} 都必须带有效令牌。
 *
 * <p>不通过时直接写 401 + JSON，前端读到可读原因后跳登录页。
 */
public class AuthInterceptor implements HandlerInterceptor {

    private final AuthService authService;

    public AuthInterceptor(AuthService authService) {
        this.authService = authService;
    }

    @Override
    public boolean preHandle(HttpServletRequest req, HttpServletResponse res, Object handler) throws Exception {
        if ("OPTIONS".equalsIgnoreCase(req.getMethod())) {
            return true; // CORS 预检不带鉴权头
        }
        String token = extractToken(req);
        String userId = authService.userOf(token);
        if (userId == null) {
            AppLog.warn("拒绝未授权请求: %s %s", req.getMethod(), req.getRequestURI());
            res.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            res.setCharacterEncoding(StandardCharsets.UTF_8.name());
            res.setContentType("application/json;charset=UTF-8");
            res.getWriter().write("{\"status\":401,\"message\":\"未登录或登录已过期\"}");
            return false;
        }
        req.setAttribute(AuthSupport.ATTR_USER, userId);
        req.setAttribute(AuthSupport.ATTR_TOKEN, token);
        return true;
    }

    /** 令牌从 Authorization / X-Token 头取，也允许查询串带（便于 SSE 调试） */
    private static String extractToken(HttpServletRequest req) {
        String auth = req.getHeader("Authorization");
        if (auth != null && auth.startsWith("Bearer ")) {
            return auth.substring(7).trim();
        }
        String header = req.getHeader("X-Token");
        if (header != null && !header.isBlank()) {
            return header.trim();
        }
        String q = req.getParameter("token");
        return q == null || q.isBlank() ? null : q.trim();
    }
}
