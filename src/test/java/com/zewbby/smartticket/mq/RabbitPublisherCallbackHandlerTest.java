package com.zewbby.smartticket.mq;

import com.zewbby.smartticket.service.LocalMessageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.core.ReturnedMessage;
import org.springframework.amqp.rabbit.connection.CorrelationData;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class RabbitPublisherCallbackHandlerTest {

    @Mock
    private LocalMessageService localMessageService;

    private RabbitPublisherCallbackHandler callbackHandler;

    @BeforeEach
    void setUp() {
        callbackHandler = new RabbitPublisherCallbackHandler(localMessageService);
    }

    @Test
    void confirmAckMarksLocalMessageConfirmed() {
        callbackHandler.confirm(new CorrelationData("MSG1"), true, null);

        verify(localMessageService).markConfirmed("MSG1");
    }

    @Test
    void confirmNackMarksLocalMessageFailed() {
        callbackHandler.confirm(new CorrelationData("MSG1"), false, "nack");

        verify(localMessageService).markPublishFailedByMessageId("MSG1", "Broker nack: nack");
    }

    @Test
    void returnedMessageMarksLocalMessageFailed() {
        MessageProperties properties = new MessageProperties();
        properties.setCorrelationId("MSG1");
        Message message = new Message(new byte[0], properties);
        ReturnedMessage returnedMessage = new ReturnedMessage(message, 312, "NO_ROUTE", "exchange", "bad.routing");

        callbackHandler.returnedMessage(returnedMessage);

        verify(localMessageService).markReturnedByMessageId(
                org.mockito.ArgumentMatchers.eq("MSG1"),
                org.mockito.ArgumentMatchers.contains("NO_ROUTE")
        );
    }

    @Test
    void confirmWithoutCorrelationIdDoesNotThrowOrUpdateDb() {
        callbackHandler.confirm(null, true, null);

        verify(localMessageService, never()).markConfirmed(org.mockito.ArgumentMatchers.anyString());
    }
}
