package com.zewbby.smartticket.config;

import com.zewbby.smartticket.common.RequestCostInterceptor;
import com.zewbby.smartticket.ratelimit.RateLimitInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    private final RequestCostInterceptor requestCostInterceptor;

    private final RateLimitInterceptor rateLimitInterceptor;

    public WebMvcConfig(RequestCostInterceptor requestCostInterceptor,
                        RateLimitInterceptor rateLimitInterceptor) {
        this.requestCostInterceptor = requestCostInterceptor;
        this.rateLimitInterceptor = rateLimitInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(requestCostInterceptor)
                .addPathPatterns("/**")
                .excludePathPatterns("/actuator/**");

        registry.addInterceptor(rateLimitInterceptor)
                .addPathPatterns("/api/orders/**", "/api/order-requests/**");
    }
}
