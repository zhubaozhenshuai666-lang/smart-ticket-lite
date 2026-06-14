package com.zewbby.smartticket.auth;

import com.zewbby.smartticket.common.BusinessException;
import com.zewbby.smartticket.constant.ErrorMessageConstant;
import com.zewbby.smartticket.domain.entity.UserAccount;
import com.zewbby.smartticket.enums.UserRoleEnum;
import com.zewbby.smartticket.enums.UserStatusEnum;
import com.zewbby.smartticket.mapper.UserMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

@Component
public class AdminAuthorizationInterceptor implements HandlerInterceptor {

    private final UserMapper userMapper;

    public AdminAuthorizationInterceptor(UserMapper userMapper) {
        this.userMapper = userMapper;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }

        /*
         * 后台接口不能只靠前端隐藏按钮。前端隐藏只是用户体验，真正的权限边界必须在服务端。
         * /api/admin/** 可以重试消息、修复库存、补偿失败请求，这些操作会改变交易系统状态；
         * 普通购票 USER 一旦能访问，就可能人为制造重复投递、错误补偿或库存错乱。
         */
        Long userId = UserContext.requireUserId();
        UserAccount user = userMapper.selectById(userId);
        if (user == null) {
            throw new BusinessException(401, ErrorMessageConstant.UNAUTHORIZED);
        }
        if (!UserStatusEnum.isNormal(user.getStatus())) {
            throw new BusinessException(403, ErrorMessageConstant.ACCOUNT_UNAVAILABLE);
        }

        /*
         * 当前阶段只做轻量角色模型：
         * USER 只能购票；OPERATOR 可以看后台数据和执行低风险检查；ADMIN 可以做重试、修复、补偿等高风险操作。
         * 不上完整 RBAC 是为了避免在交易主链路还没完全压测前，把项目复杂度堆到权限平台上。
         */
        String roleCode = UserRoleEnum.normalize(user.getRoleCode()).getCode();
        if (!UserRoleEnum.isBackstageRole(roleCode)) {
            throw new BusinessException(403, ErrorMessageConstant.NO_ADMIN_PERMISSION);
        }
        if (UserRoleEnum.isOperator(roleCode) && !isOperatorAllowed(request)) {
            throw new BusinessException(403, ErrorMessageConstant.NO_ADMIN_PERMISSION);
        }
        return true;
    }

    private boolean isOperatorAllowed(HttpServletRequest request) {
        if ("GET".equalsIgnoreCase(request.getMethod())) {
            return true;
        }
        String uri = request.getRequestURI();
        return "POST".equalsIgnoreCase(request.getMethod())
                && (uri.startsWith("/api/admin/stocks/consistency/check/")
                || "/api/admin/stocks/consistency/check-all".equals(uri)
                || uri.matches("^/api/admin/ticket-categories/\\d+/stock/preheat$")
                || "/api/admin/stocks/preheat-all".equals(uri)
                || "/api/admin/stocks/preload".equals(uri)
                || uri.matches("^/api/admin/stocks/\\d+/preload$"));
    }
}
