package com.zewbby.smartticket.common;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

@Component
public class RequestCostInterceptor implements HandlerInterceptor {

    private static final Logger LOGGER = LoggerFactory.getLogger(RequestCostInterceptor.class);

    private static final String START_TIME_ATTRIBUTE = "requestStartTime";

    private static final long SLOW_REQUEST_THRESHOLD_MS = 1000L;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        request.setAttribute(START_TIME_ATTRIBUTE, System.currentTimeMillis());
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request,
                                HttpServletResponse response,
                                Object handler,
                                Exception ex) {
        Object startTimeValue = request.getAttribute(START_TIME_ATTRIBUTE);
        if (!(startTimeValue instanceof Long startTime)) {
            return;
        }

        long costMs = System.currentTimeMillis() - startTime;
        String method = request.getMethod();
        String uri = request.getRequestURI();
        int status = response.getStatus();
        String clientIp = resolveClientIp(request);

        if (costMs > SLOW_REQUEST_THRESHOLD_MS) {
            LOGGER.warn("Slow API request, method={}, uri={}, status={}, costMs={}, clientIp={}",
                    method, uri, status, costMs, clientIp);
            return;
        }

        LOGGER.info("API request, method={}, uri={}, status={}, costMs={}, clientIp={}",
                method, uri, status, costMs, clientIp);
    }

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
