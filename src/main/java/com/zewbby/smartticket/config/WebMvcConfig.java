package com.zewbby.smartticket.config;

import com.zewbby.smartticket.auth.JwtAuthenticationInterceptor;
import com.zewbby.smartticket.auth.AdminAuthorizationInterceptor;
import com.zewbby.smartticket.common.RequestCostInterceptor;
import com.zewbby.smartticket.ratelimit.RateLimitInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    private final RequestCostInterceptor requestCostInterceptor;

    private final JwtAuthenticationInterceptor jwtAuthenticationInterceptor;

    private final AdminAuthorizationInterceptor adminAuthorizationInterceptor;

    private final RateLimitInterceptor rateLimitInterceptor;

    public WebMvcConfig(RequestCostInterceptor requestCostInterceptor,
                        JwtAuthenticationInterceptor jwtAuthenticationInterceptor,
                        AdminAuthorizationInterceptor adminAuthorizationInterceptor,
                        RateLimitInterceptor rateLimitInterceptor) {
        this.requestCostInterceptor = requestCostInterceptor;
        this.jwtAuthenticationInterceptor = jwtAuthenticationInterceptor;
        this.adminAuthorizationInterceptor = adminAuthorizationInterceptor;
        this.rateLimitInterceptor = rateLimitInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(requestCostInterceptor)
                .addPathPatterns("/**")
                .excludePathPatterns("/actuator/**");

        registry.addInterceptor(jwtAuthenticationInterceptor)
                .addPathPatterns(
                        "/api/orders/**",
                        "/api/order-requests/**",
                        "/api/payments/**",
                        "/api/auth/logout",
                        "/api/users/me",
                        "/api/users/me/orders",
                        "/api/users/*/orders",
                        "/api/admin/**"
                )
                .excludePathPatterns(
                        "/api/auth/register",
                        "/api/auth/login",
                        "/api/shows/**",
                        "/api/sessions/**",
                        "/error",
                        "/actuator/**"
                );

        registry.addInterceptor(adminAuthorizationInterceptor)
                .addPathPatterns("/api/admin/**");

        registry.addInterceptor(rateLimitInterceptor)
                .addPathPatterns("/api/orders/**", "/api/order-requests/**", "/api/payments/**");
    }
}
