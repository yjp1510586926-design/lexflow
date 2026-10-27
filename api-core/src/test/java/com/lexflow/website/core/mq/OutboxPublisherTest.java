package com.lexflow.website.core.mq;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lexflow.dao.OutboxEventMapper;
import com.lexflow.dao.model.OutboxEventModel;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.core.ReturnedMessage;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

class OutboxPublisherTest {

    @Test
    void routedMessageIsSentButReturnedMessageIsRetried() throws Exception {
        for (boolean returned : new boolean[] { false, true }) {
            var mapper = mock(OutboxEventMapper.class);
            var rabbit = mock(RabbitTemplate.class);
            var row = new OutboxEventModel();
            row.setEventId("event-test");
            row.setRoutingKey("document.parse.requested.v1");
            row.setPayload("{\"taskId\":\"123\"}");
            row.setAttempt(0);
            when(mapper.selectPendingEvents(20)).thenReturn(List.of(row));
            doAnswer(inv -> {
                assertFalse(inv.getArgument(2) instanceof String, "不要二次编码 JSON 字符串");
                CorrelationData cd = inv.getArgument(3);
                if (returned) cd.setReturned(
                    new ReturnedMessage(
                        new Message(new byte[0], new MessageProperties()),
                        312,
                        "NO_ROUTE",
                        "lexflow.tasks",
                        row.getRoutingKey()
                    )
                );
                cd.getFuture().complete(new CorrelationData.Confirm(true, null));
                return null;
            })
                .when(rabbit)
                .convertAndSend(
                    anyString(),
                    anyString(),
                    any(Object.class),
                    any(CorrelationData.class)
                );
            new OutboxPublisher(mapper, rabbit, new ObjectMapper()).publish();
            if (returned) {
                verify(mapper, never()).markSent(anyString(), anyInt());
                verify(mapper).scheduleRetry("event-test", 1, 2L);
            } else verify(mapper).markSent("event-test", 1);
        }
    }
}
