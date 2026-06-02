package com.zewbby.smartticket.constant;

public final class ErrorMessageConstant {

    public static final String USER_NOT_FOUND = "用户不存在";

    public static final String USER_DISABLED = "用户状态不可用";

    public static final String ACCOUNT_UNAVAILABLE = "账号不可用";

    public static final String USERNAME_EXISTS = "用户名已存在";

    public static final String PHONE_EXISTS = "手机号已存在";

    public static final String PASSWORD_ERROR = "密码错误";

    public static final String ACCOUNT_OR_PASSWORD_ERROR = "账号或密码错误";

    public static final String LOGIN_LOCKED = "登录失败次数过多，请稍后再试";

    public static final String PASSWORD_WEAK = "密码强度不符合要求";

    public static final String UNAUTHORIZED = "请先登录";

    public static final String NO_ADMIN_PERMISSION = "无权限访问后台接口";

    public static final String TOKEN_INVALID = "token无效";

    public static final String TOKEN_EXPIRED = "token已过期";

    public static final String TOKEN_LOGGED_OUT = "token已退出登录";

    public static final String AUTH_SERVICE_UNAVAILABLE = "认证服务暂时不可用";

    public static final String SHOW_NOT_FOUND = "演出不存在";

    public static final String TICKET_CATEGORY_NOT_FOUND = "票档不存在";

    public static final String SHOW_SESSION_TICKET_CATEGORY_NOT_MATCH = "演出、场次或票档关系不匹配";

    public static final String STOCK_NOT_FOUND = "库存记录不存在";

    public static final String STOCK_NOT_ENOUGH = "库存不足";

    public static final String TICKET_SOLD_OUT = "票档已售罄";

    public static final String STOCK_NOT_PREHEATED = "库存未预热，请先预热库存";

    public static final String ORDER_NOT_FOUND = "订单不存在";

    public static final String ORDER_STATUS_NOT_ALLOWED = "当前订单状态不允许该操作";

    public static final String ORDER_REPEAT_SUBMIT = "请勿重复提交订单";

    public static final String ORDER_REPEAT_PAY = "订单已支付，请勿重复支付";

    public static final String ORDER_REPEAT_CANCEL = "订单已取消，请勿重复取消";

    public static final String ORDER_EXPIRED = "订单已过期，无法支付";

    public static final String ORDER_REQUEST_NOT_FOUND = "异步下单请求不存在";

    public static final String PAYMENT_REQUIRED = "请先创建支付单后再支付";

    public static final String PAYMENT_NOT_FOUND = "支付单不存在";

    public static final String PAYMENT_CREATE_FAILED = "支付单创建失败";

    public static final String PAYMENT_CHANNEL_NOT_SUPPORTED = "支付渠道不支持";

    public static final String PAYMENT_STATUS_NOT_ALLOWED = "当前支付单状态不允许该操作";

    public static final String PAYMENT_SIGNATURE_INVALID = "支付回调签名无效";

    public static final String PAYMENT_CALLBACK_EXPIRED = "支付回调已过期";

    public static final String PAYMENT_CALLBACK_REPLAY = "支付回调重复提交";

    public static final String STOCK_CONFIRM_FAILED = "库存确认失败";

    public static final String RATE_LIMITED = "请求过于频繁，请稍后再试";

    public static final String IDEMPOTENCY_TOKEN_INVALID = "幂等 token 无效或已过期";

    public static final String IDEMPOTENCY_TOKEN_USED = "请勿重复提交";

    private ErrorMessageConstant() {
    }
}
