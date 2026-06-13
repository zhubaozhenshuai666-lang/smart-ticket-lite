package com.zewbby.smartticket.ratelimit;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;

@Component
public class ClientIpResolver {

    /**
     * 解析客户端 IP。
     *
     * 当前项目还没有网关层，所以这里按常见顺序读取 X-Forwarded-For、X-Real-IP 和 remoteAddr。
     * 生产环境不能无条件相信客户端自己传的 X-Forwarded-For，应该由可信网关清洗并覆盖这些头，
     * 否则恶意用户可以伪造 IP 绕过 IP 维度限流。
     */
    public String resolve(HttpServletRequest request) {
        String forwardedFor = request.getHeader("X-Forwarded-For");
        if (forwardedFor != null && !forwardedFor.isBlank()) {
            return forwardedFor.split(",")[0].trim();
        }

        String realIp = request.getHeader("X-Real-IP");
        if (realIp != null && !realIp.isBlank()) {
            return realIp.trim();
        }

        return request.getRemoteAddr();
    }
}
