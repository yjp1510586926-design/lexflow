package com.lexflow.website.core.mq;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lexflow.website.biz.DocumentExportBiz;
import com.lexflow.website.core.config.RabbitMqConfig;
import com.rabbitmq.client.Channel;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** 手动确认导出投递；业务层凭执行批次和状态版本忽略重复或迟到事件，不重复生成同一有效产物。 */
@Component
@ConditionalOnProperty(name = "scheduling.enabled", havingValue = "true", matchIfMissing = true)
public class DocumentExportConsumer {

    private final DocumentExportBiz exports;
    private final ObjectMapper json;

    public DocumentExportConsumer(DocumentExportBiz exports, ObjectMapper json) {
        this.exports = exports;
        this.json = json;
    }

    @RabbitListener(
        queues = RabbitMqConfig.QUEUE_DOCUMENT_EXPORT,
        ackMode = "MANUAL",
        autoStartup = "${lexflow.export.consumer-enabled:true}"
    )
    public void consume(Message message, Channel channel) throws Exception {
        long tag = message.getMessageProperties().getDeliveryTag();
        try {
            if (message.getBody().length > 8192) {
                throw new IllegalArgumentException("导出消息过大");
            }
            var body = json.readTree(message.getBody());
            if (
                !"EXPORT".equals(body.path("type").asText()) ||
                !"1.0".equals(body.path("schemaVersion").asText()) ||
                !body.path("attempt").isInt() ||
                body.path("attempt").asInt() < 0 ||
                !body.path("stateVersion").isInt() ||
                body.path("stateVersion").asInt() < 1
            ) {
                throw new IllegalArgumentException("导出消息无效");
            }
            long tenant = id(body.path("tenantId").asText()),
                task = id(body.path("taskId").asText());
            exports.process(
                tenant,
                task,
                body.path("attempt").asInt(),
                body.path("stateVersion").asInt()
            );
        } catch (Exception e) {
            // 拒绝后进入死信，避免立即重新入队形成忙循环；执行中任务由租约恢复，排队任务可人工重投。
            channel.basicNack(tag, false, false);
            return;
        }
        // 业务处理正常返回后才确认；ACK 丢失仍会重投，去重依据在数据库而非 broker delivery tag。
        channel.basicAck(tag, false);
    }

    private static long id(String value) {
        if (!value.matches("[1-9][0-9]{0,18}")) {
            throw new IllegalArgumentException("编号无效");
        }
        long id = Long.parseLong(value);
        if (id <= 0) {
            throw new IllegalArgumentException("编号无效");
        }
        return id;
    }
}
