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
@TableName("local_message")
public class LocalMessage {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String messageId;

    private String businessType;

    private String businessKey;

    private String exchangeName;

    private String routingKey;

    private String payload;

    private String status;

    private Integer retryCount;

    private Integer maxRetryCount;

    private LocalDateTime nextRetryTime;

    private String lastError;

    private LocalDateTime sentAt;

    private LocalDateTime confirmedAt;

    private LocalDateTime returnedAt;

    private LocalDateTime deadAt;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
