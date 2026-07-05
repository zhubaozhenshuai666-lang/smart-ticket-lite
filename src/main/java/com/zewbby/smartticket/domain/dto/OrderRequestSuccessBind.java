package com.zewbby.smartticket.domain.dto;

public class OrderRequestSuccessBind {

    private final Long requestDbId;

    private final Long orderId;

    public OrderRequestSuccessBind(Long requestDbId, Long orderId) {
        this.requestDbId = requestDbId;
        this.orderId = orderId;
    }

    public Long getRequestDbId() {
        return requestDbId;
    }

    public Long getOrderId() {
        return orderId;
    }
}
