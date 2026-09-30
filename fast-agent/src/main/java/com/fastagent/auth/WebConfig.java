package com.fastagent.auth;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** 注册鉴权拦截器。 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final AuthService authService;

    public WebConfig(AuthService authService) {
        this.authService = authService;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new AuthInterceptor(authService))
                .addPathPatterns("/api/**")
                .excludePathPatterns(
                        "/api/auth/login",
                        // 探活接口不需要登录：无窗口探针要在没有 token 的情况下自检
                        "/api/system/health");
    }
}
