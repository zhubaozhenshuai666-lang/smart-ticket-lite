package com.zewbby.smartticket.mapper;

import com.zewbby.smartticket.domain.entity.PaymentOrder;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;

public interface PaymentMapper {

    int insert(PaymentOrder paymentOrder);

    PaymentOrder selectByPaymentNo(String paymentNo);

    PaymentOrder selectByPaymentNoAndUserId(@Param("paymentNo") String paymentNo,
                                            @Param("userId") Long userId);

    PaymentOrder selectByOrderIdAndUserId(@Param("orderId") Long orderId,
                                          @Param("userId") Long userId);

    PaymentOrder selectByOrderId(@Param("orderId") Long orderId);

    int markSuccess(@Param("paymentNo") String paymentNo,
                    @Param("paidAt") LocalDateTime paidAt,
                    @Param("callbackAt") LocalDateTime callbackAt);

    int markFailed(@Param("paymentNo") String paymentNo,
                   @Param("callbackAt") LocalDateTime callbackAt,
                   @Param("failReason") String failReason);

    int closeUnpaidByOrderId(@Param("orderId") Long orderId,
                             @Param("closedAt") LocalDateTime closedAt);
}
