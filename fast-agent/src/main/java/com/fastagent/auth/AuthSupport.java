package com.fastagent.auth;

import com.fastagent.service.ApiException;
import jakarta.servlet.http.HttpServletRequest;

/**
 * 当前登录用户的读取入口。
 *
 * <p>刻意用 request attribute 而不是 ThreadLocal：SSE 是异步请求，
 * Tomcat 线程会被复用，ThreadLocal 一旦残留，下一个请求就可能读到
 * 上一个请求的身份 —— 那是鉴权绕过。
 */
public final class AuthSupport {

    public static final String ATTR_USER = "fastagent.userId";
    public static final String ATTR_TOKEN = "fastagent.token";

    private AuthSupport() {
    }

    public static String userIdOf(HttpServletRequest req) {
        Object v = req.getAttribute(ATTR_USER);
        return v == null ? null : v.toString();
    }

    /** 取当前用户，未登录直接抛 401 */
    public static String requireUserId(HttpServletRequest req) {
        String u = userIdOf(req);
        if (u == null || u.isBlank()) {
            throw new ApiException(401, "未登录或登录已过期");
        }
        return u;
    }

    public static String tokenOf(HttpServletRequest req) {
        Object v = req.getAttribute(ATTR_TOKEN);
        return v == null ? null : v.toString();
    }
}
