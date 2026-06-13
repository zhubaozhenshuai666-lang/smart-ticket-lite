package com.zewbby.smartticket.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("payment_callback_log")
public class PaymentCallbackLog {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String paymentNo;

    private Long orderId;

    private Long userId;

    private String channel;

    private String rawBody;

    private String headers;

    private String signature;

    private String verifyResult;

    private String processResult;

    private String errorMessage;

    private LocalDateTime callbackTime;

    private LocalDateTime createdAt;
}
