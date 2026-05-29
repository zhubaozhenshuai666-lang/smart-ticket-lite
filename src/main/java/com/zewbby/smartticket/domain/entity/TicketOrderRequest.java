package com.zewbby.smartticket.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
@TableName("ticket_order_request")
public class TicketOrderRequest {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String requestId;

    private Long userId;

    private Long showId;

    private Long sessionId;

    private Long ticketCategoryId;

    private Integer quantity;

    private String status;

    private Long orderId;

    private String failReason;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
