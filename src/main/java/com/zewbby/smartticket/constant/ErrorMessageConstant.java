package com.zewbby.smartticket.constant;

public final class ErrorMessageConstant {

    public static final String USER_NOT_FOUND = "用户不存在";

    public static final String SHOW_NOT_FOUND = "演出不存在";

    public static final String TICKET_CATEGORY_NOT_FOUND = "票档不存在";

    public static final String STOCK_NOT_FOUND = "库存记录不存在";

    public static final String STOCK_NOT_ENOUGH = "库存不足";

    public static final String ORDER_NOT_FOUND = "订单不存在";

    public static final String ORDER_STATUS_NOT_ALLOWED = "当前订单状态不允许该操作";

    public static final String ORDER_REPEAT_SUBMIT = "请勿重复提交订单";

    public static final String ORDER_REPEAT_PAY = "订单已支付，请勿重复支付";

    public static final String ORDER_REPEAT_CANCEL = "订单已取消，请勿重复取消";

    public static final String ORDER_EXPIRED = "订单已过期，无法支付";

    public static final String ORDER_REQUEST_NOT_FOUND = "异步下单请求不存在";

    public static final String RATE_LIMITED = "请求过于频繁，请稍后再试";

    public static final String IDEMPOTENCY_TOKEN_INVALID = "幂等 token 无效或已过期";

    public static final String IDEMPOTENCY_TOKEN_USED = "请勿重复提交";

    private ErrorMessageConstant() {
    }
}
