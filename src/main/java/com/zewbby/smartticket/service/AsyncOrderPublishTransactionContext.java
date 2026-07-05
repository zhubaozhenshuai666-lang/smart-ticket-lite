package com.zewbby.smartticket.service;

import com.zewbby.smartticket.mq.AsyncCreateOrderMessage;

public class AsyncOrderPublishTransactionContext {

    private final String messageId;

    private final AsyncCreateOrderMessage message;

    private final AsyncOrderPublishLocalTransaction localTransaction;

    private RuntimeException failure;

    public AsyncOrderPublishTransactionContext(String messageId,
                                               AsyncCreateOrderMessage message,
                                               AsyncOrderPublishLocalTransaction localTransaction) {
        this.messageId = messageId;
        this.message = message;
        this.localTransaction = localTransaction;
    }

    public String getMessageId() {
        return messageId;
    }

    public AsyncCreateOrderMessage getMessage() {
        return message;
    }

    public void executeLocalTransaction() {
        localTransaction.execute();
    }

    public RuntimeException getFailure() {
        return failure;
    }

    public void setFailure(RuntimeException failure) {
        this.failure = failure;
    }
}
