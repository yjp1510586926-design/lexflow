package com.lexflow.website.core.mq;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lexflow.dao.OutboxEventMapper;
import com.lexflow.dao.model.OutboxEventModel;
import com.lexflow.website.core.config.RabbitMqConfig;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Outbox 发布器：每 5 秒轮询 outbox_event 中 PENDING 记录，
 * 使用 publisher confirm 确认且未被退回后标记 SENT；第 5 次失败标记 FAILED。
 *
 * 架构保证：业务服务在同一事务内写入 async_task + outbox_event，
 * 发布器只负责异步投递，不参与业务事务，保障至少一次投递语义。
 * broker 确认后、数据库标记前发生故障仍可能重复发送，消费者必须校验任务执行版本。
 */
@Component
@ConditionalOnProperty(name = "scheduling.enabled", havingValue = "true", matchIfMissing = true)
public class OutboxPublisher {

    private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);
    private static final int MAX_ATTEMPT = 5;
    private static final int BATCH_SIZE = 20;

    private final OutboxEventMapper outboxEventMapper;
    private final RabbitTemplate rabbit;
    private final ObjectMapper json;

    public OutboxPublisher(
        OutboxEventMapper outboxEventMapper,
        RabbitTemplate rabbit,
        ObjectMapper json
    ) {
        this.outboxEventMapper = outboxEventMapper;
        this.rabbit = rabbit;
        this.json = json;
    }

    @Scheduled(fixedDelay = 5000, initialDelay = 10000)
    public void publish() {
        List<OutboxEventModel> rows = outboxEventMapper.selectPendingEvents(BATCH_SIZE);

        for (OutboxEventModel row : rows) {
            String eventId = row.getEventId();
            String routingKey = row.getRoutingKey();
            String payload = row.getPayload();
            int attempt = (row.getAttempt() != null ? row.getAttempt() : 0) + 1;

            try {
                var correlationData = new CorrelationData(eventId);
                rabbit.convertAndSend(
                    RabbitMqConfig.TASKS_EXCHANGE,
                    routingKey,
                    json.readTree(payload),
                    correlationData
                );

                var confirmation = correlationData.getFuture().get(3, TimeUnit.SECONDS);
                if (confirmation == null || !confirmation.isAck()) {
                    throw new RuntimeException("broker did not acknowledge publication");
                }

                if (correlationData.getReturned() != null) {
                    // broker ACK 只表示接收，mandatory 退回表示未路由到队列，不能记为已投递。
                    throw new IllegalStateException("message was not routed to a queue");
                }
                outboxEventMapper.markSent(eventId, attempt);
                log.debug("[outbox] SENT eventId={} routingKey={}", eventId, routingKey);
            } catch (Exception ex) {
                log.warn(
                    "[outbox] FAILED eventId={} attempt={} error={}",
                    eventId,
                    attempt,
                    ex.getMessage()
                );
                long backoffSeconds = (long) Math.min(Math.pow(2, attempt), 300);
                if (attempt >= MAX_ATTEMPT) {
                    outboxEventMapper.markFailed(eventId, attempt);
                } else {
                    outboxEventMapper.scheduleRetry(eventId, attempt, backoffSeconds);
                }
            }
        }
    }
}
