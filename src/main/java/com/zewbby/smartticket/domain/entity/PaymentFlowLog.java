package com.zewbby.smartticket.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("payment_flow_log")
public class PaymentFlowLog {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String paymentNo;

    private Long orderId;

    private String fromStatus;

    private String toStatus;

    private String eventType;

    private BigDecimal amount;

    private String result;

    private String reason;

    private LocalDateTime createdAt;
}
