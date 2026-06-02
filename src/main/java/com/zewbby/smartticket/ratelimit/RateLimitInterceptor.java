package com.zewbby.smartticket.ratelimit;

import com.zewbby.smartticket.common.BusinessException;
import com.zewbby.smartticket.config.RateLimitProperties;
import com.zewbby.smartticket.constant.ErrorMessageConstant;
import com.zewbby.smartticket.constant.RedisKeyConstant;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

@Component
public class RateLimitInterceptor implements HandlerInterceptor {

    private final RateLimitService rateLimitService;

    private final ClientIpResolver clientIpResolver;

    private final RateLimitProperties rateLimitProperties;

    public RateLimitInterceptor(RateLimitService rateLimitService,
                                ClientIpResolver clientIpResolver,
                                RateLimitProperties rateLimitProperties) {
        this.rateLimitService = rateLimitService;
        this.clientIpResolver = clientIpResolver;
        this.rateLimitProperties = rateLimitProperties;
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
        String clientIp = clientIpResolver.resolve(request);

        //ip限流
        boolean ipAllowed = rateLimitService.tryAcquireTokenBucket(
                RedisKeyConstant.rateLimitIpKey(clientIp, uri),
                rateLimitProperties.getOrderIpCapacity(),
                rateLimitProperties.getOrderIpRefillRatePerSecond(),
                1,
                rateLimitProperties.getKeyTtlSeconds()
        );
        //限流了
        if (!ipAllowed) {
            throw new BusinessException(ErrorMessageConstant.RATE_LIMITED);
        }

        //接口限流
        boolean apiAllowed = rateLimitService.tryAcquireTokenBucket(
                RedisKeyConstant.rateLimitApiKey(uri),
                rateLimitProperties.getOrderApiCapacity(),
                rateLimitProperties.getOrderApiRefillRatePerSecond(),
                1,
                rateLimitProperties.getKeyTtlSeconds()
        );
        if (!apiAllowed) {
            throw new BusinessException(ErrorMessageConstant.RATE_LIMITED);
        }

        return true;
    }
}
