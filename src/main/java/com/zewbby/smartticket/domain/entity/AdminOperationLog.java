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
@TableName("admin_operation_log")
public class AdminOperationLog {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long operatorUserId;

    private String operatorUsername;

    private String operatorRole;

    private String operationType;

    private String resourceType;

    private String resourceId;

    private String requestUri;

    private String requestMethod;

    private String requestParams;

    private String operationResult;

    private String errorMessage;

    private String clientIp;

    private String traceId;

    private LocalDateTime createdAt;
}
