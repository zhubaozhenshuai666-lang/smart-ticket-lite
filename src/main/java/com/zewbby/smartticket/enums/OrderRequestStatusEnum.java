package com.zewbby.smartticket.enums;

public enum OrderRequestStatusEnum {

    INIT("INIT", "请求已创建，尚未预扣库存"),
    PRE_DEDUCTED("PRE_DEDUCTED", "Redis库存已预扣"),
    QUEUED("QUEUED", "消息已进入发送链路"),
    PROCESSING("PROCESSING", "消费者处理中"),
    SUCCESS("SUCCESS", "处理成功"),
    FAILED("FAILED", "处理失败"),
    COMPENSATED("COMPENSATED", "失败请求已补偿库存"),
    CANCELLED("CANCELLED", "请求已取消");

    private final String code;

    private final String description;

    OrderRequestStatusEnum(String code, String description) {
        this.code = code;
        this.description = description;
    }

    public String getCode() {
        return code;
    }

    public String getDescription() {
        return description;
    }

    public static boolean canEnterProcessing(String status) {
        return PRE_DEDUCTED.code.equals(status) || QUEUED.code.equals(status);
    }

    public static boolean isTerminal(String status) {
        return SUCCESS.code.equals(status)
                || FAILED.code.equals(status)
                || COMPENSATED.code.equals(status)
                || CANCELLED.code.equals(status);
    }
}
