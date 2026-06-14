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
@TableName("dead_letter_message")
public class DeadLetterMessage {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String messageId;

    private String businessType;

    private String businessKey;

    private String queueName;

    private String exchangeName;

    private String routingKey;

    private String payload;

    private String exceptionType;

    private String exceptionMessage;

    private String status;

    private Integer retryCount;

    private Integer maxRetryCount;

    private LocalDateTime lastRetryAt;

    private LocalDateTime resolvedAt;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
