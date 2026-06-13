package com.zewbby.smartticket.auth;

import com.zewbby.smartticket.common.BusinessException;
import com.zewbby.smartticket.constant.ErrorMessageConstant;

public final class UserContext {

    private static final ThreadLocal<Long> USER_ID_HOLDER = new ThreadLocal<>();

    private static final ThreadLocal<String> USERNAME_HOLDER = new ThreadLocal<>();

    private static final ThreadLocal<String> ROLE_CODE_HOLDER = new ThreadLocal<>();

    private UserContext() {
    }

    public static void setUserId(Long userId) {
        USER_ID_HOLDER.set(userId);
    }

    public static void setUser(Long userId, String username, String roleCode) {
        USER_ID_HOLDER.set(userId);
        USERNAME_HOLDER.set(username);
        ROLE_CODE_HOLDER.set(roleCode);
    }

    public static Long getUserId() {
        return USER_ID_HOLDER.get();
    }

    public static String getUsername() {
        return USERNAME_HOLDER.get();
    }

    public static String getRoleCode() {
        return ROLE_CODE_HOLDER.get();
    }

    public static Long requireUserId() {
        Long userId = getUserId();
        if (userId == null) {
            throw new BusinessException(401, ErrorMessageConstant.UNAUTHORIZED);
        }
        return userId;
    }

    public static void clear() {
        USER_ID_HOLDER.remove();
        USERNAME_HOLDER.remove();
        ROLE_CODE_HOLDER.remove();
    }
}
