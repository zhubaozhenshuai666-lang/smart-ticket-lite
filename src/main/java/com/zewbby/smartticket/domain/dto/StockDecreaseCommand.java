package com.zewbby.smartticket.domain.dto;

public class StockDecreaseCommand {

    private final Long ticketCategoryId;

    private final Integer bucketVersion;

    private final Integer bucketNo;

    private final Integer quantity;

    public StockDecreaseCommand(Long ticketCategoryId, Integer quantity) {
        this(ticketCategoryId, null, null, quantity);
    }

    public StockDecreaseCommand(Long ticketCategoryId, Integer bucketVersion, Integer bucketNo, Integer quantity) {
        this.ticketCategoryId = ticketCategoryId;
        this.bucketVersion = bucketVersion;
        this.bucketNo = bucketNo;
        this.quantity = quantity;
    }

    public Long getTicketCategoryId() {
        return ticketCategoryId;
    }

    public Integer getBucketVersion() {
        return bucketVersion;
    }

    public Integer getBucketNo() {
        return bucketNo;
    }

    public Integer getQuantity() {
        return quantity;
    }
}
