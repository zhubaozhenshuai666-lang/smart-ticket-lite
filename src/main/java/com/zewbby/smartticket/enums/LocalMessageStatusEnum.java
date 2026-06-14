package com.zewbby.smartticket.enums;

public enum LocalMessageStatusEnum {

    INIT("INIT", "已入库，等待发送"),
    SENDING("SENDING", "发送器已抢占，准备发送"),
    SENT("SENT", "已调用RabbitTemplate，等待Broker Confirm"),
    CONFIRMED("CONFIRMED", "Broker已确认收到消息"),
    FAILED("FAILED", "发送失败，等待重试"),
    DEAD("DEAD", "超过最大重试次数，不再自动重试");

    private final String code;

    private final String description;

    LocalMessageStatusEnum(String code, String description) {
        this.code = code;
        this.description = description;
    }

    public String getCode() {
        return code;
    }

    public String getDescription() {
        return description;
    }

    public static boolean canManualRetry(String status) {
        return FAILED.code.equals(status) || DEAD.code.equals(status);
    }
}
