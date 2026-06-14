package com.zewbby.smartticket.mq;

import com.zewbby.smartticket.enums.ConsumerExceptionTypeEnum;

public class ConsumerRetryableException extends RuntimeException {

    private final ConsumerExceptionTypeEnum exceptionType;

    public ConsumerRetryableException(ConsumerExceptionTypeEnum exceptionType, String message, Throwable cause) {
        super(message, cause);
        this.exceptionType = exceptionType;
    }

    public ConsumerExceptionTypeEnum getExceptionType() {
        return exceptionType;
    }
}
