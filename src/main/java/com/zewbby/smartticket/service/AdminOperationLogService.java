package com.zewbby.smartticket.service;

import com.zewbby.smartticket.enums.AdminOperationTypeEnum;
import jakarta.servlet.http.HttpServletRequest;

public interface AdminOperationLogService {

    void recordSuccess(AdminOperationTypeEnum operationType,
                       String resourceType,
                       String resourceId,
                       HttpServletRequest request);

    void recordFailure(AdminOperationTypeEnum operationType,
                       String resourceType,
                       String resourceId,
                       Throwable error,
                       HttpServletRequest request);
}
