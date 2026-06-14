package com.zewbby.smartticket.service.impl;

import com.zewbby.smartticket.domain.entity.PaymentCallbackLog;
import com.zewbby.smartticket.domain.entity.PaymentFlowLog;
import com.zewbby.smartticket.mapper.PaymentCallbackLogMapper;
import com.zewbby.smartticket.mapper.PaymentFlowLogMapper;
import com.zewbby.smartticket.service.PaymentAuditService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PaymentAuditServiceImpl implements PaymentAuditService {

    private final PaymentCallbackLogMapper paymentCallbackLogMapper;

    private final PaymentFlowLogMapper paymentFlowLogMapper;

    public PaymentAuditServiceImpl(PaymentCallbackLogMapper paymentCallbackLogMapper,
                                   PaymentFlowLogMapper paymentFlowLogMapper) {
        this.paymentCallbackLogMapper = paymentCallbackLogMapper;
        this.paymentFlowLogMapper = paymentFlowLogMapper;
    }

    /**
     * 回调原文使用独立事务记录。
     *
     * 签名失败、订单状态非法、库存确认失败都会导致支付主事务回滚；但回调日志不能跟着回滚，
     * 否则攻击请求和异常回调反而没有证据。这里用 REQUIRES_NEW 保证“来过一次回调，就留一次记录”。
     */
    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordCallbackLog(PaymentCallbackLog callbackLog) {
        paymentCallbackLogMapper.insert(callbackLog);
    }

    /**
     * 支付流水默认参与当前业务事务。
     *
     * 支付流水描述的是支付单状态变化，如果支付单状态更新回滚，流水也应该回滚；
     * 这样不会出现“流水显示成功，但 payment_order 仍是 INIT”的假证据。
     */
    @Override
    @Transactional
    public void recordFlowLog(PaymentFlowLog flowLog) {
        paymentFlowLogMapper.insert(flowLog);
    }
}
