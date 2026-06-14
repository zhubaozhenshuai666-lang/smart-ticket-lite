package com.zewbby.smartticket.auth;

import com.zewbby.smartticket.common.BusinessException;
import com.zewbby.smartticket.constant.ErrorMessageConstant;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

@Component
public class JwtAuthenticationInterceptor implements HandlerInterceptor {

    private static final String AUTHORIZATION_HEADER = "Authorization";

    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtTokenProvider jwtTokenProvider;

    private final TokenBlacklistService tokenBlacklistService;

    public JwtAuthenticationInterceptor(JwtTokenProvider jwtTokenProvider,
                                        TokenBlacklistService tokenBlacklistService) {
        this.jwtTokenProvider = jwtTokenProvider;
        this.tokenBlacklistService = tokenBlacklistService;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        //在处理新请求前，先清空当前线程的用户上下文：为了防止在多线程复用（线程池）的情况下，上一个请求的用户数据污染了当前请求，属于严格的防御性编程。
        UserContext.clear();
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }

        try {
            //提取并初步校验 Token 格式
            String authorization = request.getHeader(AUTHORIZATION_HEADER);
            if (authorization == null || authorization.isBlank()) {
                throw new BusinessException(401, ErrorMessageConstant.UNAUTHORIZED);
            }
            //如果不以bear开头的话就抛无效异常
            if (!authorization.startsWith(BEARER_PREFIX)) {
                throw new BusinessException(401, ErrorMessageConstant.TOKEN_INVALID);
            }

            //取出来中间的token有效位
            String token = authorization.substring(BEARER_PREFIX.length()).trim();
            //验证token是否被篡改
            JwtUserClaims claims = jwtTokenProvider.parseToken(token);
            if (tokenBlacklistService.isBlacklisted(claims.getJti()) && !isLogoutRequest(request)) {
                throw new BusinessException(401, ErrorMessageConstant.TOKEN_LOGGED_OUT);
            }
            /*
             * UserContext 保存的是当前请求的登录态快照。权限拦截器和业务代码都从这里拿当前用户，
             * 但 afterCompletion 必须清理，否则 Tomcat 线程复用时会污染下一个请求。
             */
            UserContext.setUser(claims.getUserId(), claims.getUsername(), claims.getRoleCode());
            return true;
        } catch (RuntimeException exception) {
            UserContext.clear();
            throw exception;
        }
    }

    @Override
    public void afterCompletion(HttpServletRequest request,
                                HttpServletResponse response,
                                Object handler,
                                Exception ex) {
        UserContext.clear();
    }

    private boolean isLogoutRequest(HttpServletRequest request) {
        return "POST".equalsIgnoreCase(request.getMethod())
                && "/api/auth/logout".equals(request.getRequestURI());
    }
}
