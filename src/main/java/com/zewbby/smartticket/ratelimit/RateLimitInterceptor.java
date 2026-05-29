package com.zewbby.smartticket.ratelimit;

import com.zewbby.smartticket.common.BusinessException;
import com.zewbby.smartticket.constant.ErrorMessageConstant;
import com.zewbby.smartticket.constant.RedisKeyConstant;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

@Component
public class RateLimitInterceptor implements HandlerInterceptor {

    private static final RateLimitRule IP_URI_RULE = new RateLimitRule(20, 10);

    private static final RateLimitRule API_RULE = new RateLimitRule(200, 10);

    private final RateLimitService rateLimitService;
    public RateLimitInterceptor(RateLimitService rateLimitService) {
        this.rateLimitService = rateLimitService;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        //跨域探测
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }

        //获取uri
        String uri = request.getRequestURI();
        //获取客户端ip
        String clientIp = resolveClientIp(request);

        //ip限流
        boolean ipAllowed = rateLimitService.tryAcquire(
                RedisKeyConstant.rateLimitIpKey(clientIp, uri),
                IP_URI_RULE.getLimit(),
                IP_URI_RULE.getWindowSeconds()
        );
        //限流了
        if (!ipAllowed) {
            throw new BusinessException(ErrorMessageConstant.RATE_LIMITED);
        }

        //接口限流
        boolean apiAllowed = rateLimitService.tryAcquire(
                RedisKeyConstant.rateLimitApiKey(uri),
                API_RULE.getLimit(),
                API_RULE.getWindowSeconds()
        );
        if (!apiAllowed) {
            throw new BusinessException(ErrorMessageConstant.RATE_LIMITED);
        }

        return true;
    }

    //解析客户段ip地址
    private String resolveClientIp(HttpServletRequest request) {
        String forwardedFor = request.getHeader("X-Forwarded-For");
        if (forwardedFor != null && !forwardedFor.isBlank()) {
            return forwardedFor.split(",")[0].trim();
        }

        String realIp = request.getHeader("X-Real-IP");
        if (realIp != null && !realIp.isBlank()) {
            return realIp;
        }

        return request.getRemoteAddr();
    }
}
