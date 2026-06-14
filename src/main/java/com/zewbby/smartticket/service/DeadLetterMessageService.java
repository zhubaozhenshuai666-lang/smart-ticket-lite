package com.zewbby.smartticket.service;

import com.zewbby.smartticket.domain.entity.DeadLetterMessage;
import com.zewbby.smartticket.enums.ConsumerExceptionTypeEnum;
import com.zewbby.smartticket.mq.AsyncCreateOrderMessage;

import java.util.List;

public interface DeadLetterMessageService {

    void recordDeadLetter(String messageId,
                          String businessType,
                          String businessKey,
                          String queueName,
                          String exchangeName,
                          String routingKey,
                          String payload,
                          ConsumerExceptionTypeEnum exceptionType,
                          String exceptionMessage);

    void recordAsyncCreateOrderDeadLetter(AsyncCreateOrderMessage message,
                                          String queueName,
                                          String exchangeName,
                                          String routingKey,
                                          String messageId,
                                          ConsumerExceptionTypeEnum exceptionType,
                                          String exceptionMessage);

    List<DeadLetterMessage> selectRecent(String status, Integer limit);

    DeadLetterMessage getById(Long id);

    void retry(Long id);

    void ignore(Long id);

    void resolve(Long id);
}
