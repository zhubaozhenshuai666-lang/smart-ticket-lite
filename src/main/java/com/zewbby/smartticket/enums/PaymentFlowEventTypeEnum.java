package com.zewbby.smartticket.enums;

public enum PaymentFlowEventTypeEnum {

    CREATE_PAYMENT,

    MOCK_CALLBACK_SUCCESS,

    MOCK_CALLBACK_FAILED,

    IDEMPOTENT_REPEAT,

    CLOSE_PAYMENT
}
