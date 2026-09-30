package com.fastagent.auth;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/** 登录相关接口（当前账号写死在 {@link AuthService}）。 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/login")
    public Map<String, Object> login(@RequestBody Map<String, String> body) {
        AuthService.LoginResult r = authService.login(body.get("username"), body.get("password"));
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("token", r.token());
        m.put("userId", r.userId());
        m.put("username", r.userId());
        return m;
    }

    @PostMapping("/logout")
    public Map<String, Object> logout(HttpServletRequest req) {
        authService.logout(AuthSupport.tokenOf(req));
        return Map.of("ok", true);
    }

    /** 前端启动时用它判断是否已登录 */
    @GetMapping("/me")
    public Map<String, Object> me(HttpServletRequest req) {
        String userId = AuthSupport.requireUserId(req);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("userId", userId);
        m.put("username", userId);
        return m;
    }
}
