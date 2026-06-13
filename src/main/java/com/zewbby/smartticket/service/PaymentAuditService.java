package com.zewbby.smartticket.service;

import com.zewbby.smartticket.domain.entity.PaymentCallbackLog;
import com.zewbby.smartticket.domain.entity.PaymentFlowLog;

public interface PaymentAuditService {

    void recordCallbackLog(PaymentCallbackLog callbackLog);

    void recordFlowLog(PaymentFlowLog flowLog);
}
