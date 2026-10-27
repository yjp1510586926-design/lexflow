package com.lexflow.website.core.mq;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lexflow.website.biz.TaskDeliveryRecoveryBiz;
import com.lexflow.website.core.config.RabbitMqConfig;
import com.rabbitmq.client.Channel;
import jakarta.annotation.PreDestroy;
import java.math.BigInteger;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.AcknowledgeMode;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.listener.MessageListenerContainer;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 死信持久化入口：DLQ 没有后续死信路由，持久化失败时不能拒绝或丢弃原消息。 */
@Component
@ConditionalOnProperty(name = "scheduling.enabled", havingValue = "true", matchIfMissing = true)
public class TaskDeadLetterCollector {

    static final String PARSE_LISTENER = "lexflow-document-parse-dead-letter-collector";
    static final String INDEX_LISTENER = "lexflow-knowledge-ingest-dead-letter-collector";
    static final String CONTRACT_LISTENER = "lexflow-contract-review-dead-letter-collector";
    static final String GENERATION_LISTENER = "lexflow-document-generate-dead-letter-collector";
    static final String ANALYSIS_LISTENER = "lexflow-case-analysis-dead-letter-collector";
    static final String CONTAINER_FACTORY = "taskDeadLetterContainerFactory";
    private static final Logger log = LoggerFactory.getLogger(TaskDeadLetterCollector.class);
    private static final int MAX_BYTES = 8192;

    private final TaskDeliveryRecoveryBiz recovery;
    private final RabbitTemplate rabbit;
    private final ObjectMapper json;
    private final RabbitListenerEndpointRegistry registry;
    private final JdbcTemplate database;
    private final ExecutorService stops;
    private final long confirmTimeoutMillis;
    private final Map<String, Pause> paused = new ConcurrentHashMap<>();
    private volatile boolean closed;

    private static final class Pause {

        volatile boolean stopping;
        volatile boolean stopped;
    }

    @Autowired
    public TaskDeadLetterCollector(
        TaskDeliveryRecoveryBiz recovery,
        RabbitTemplate rabbit,
        ObjectMapper json,
        RabbitListenerEndpointRegistry registry,
        JdbcTemplate database
    ) {
        this(
            recovery,
            rabbit,
            json,
            registry,
            database,
            Executors.newSingleThreadExecutor(task -> {
                var thread = new Thread(task, "lexflow-dead-letter-container-stop");
                thread.setDaemon(true);
                return thread;
            }),
            5000
        );
    }

    TaskDeadLetterCollector(
        TaskDeliveryRecoveryBiz recovery,
        RabbitTemplate rabbit,
        ObjectMapper json,
        RabbitListenerEndpointRegistry registry,
        JdbcTemplate database,
        ExecutorService stops,
        long confirmTimeoutMillis
    ) {
        this.recovery = recovery;
        this.rabbit = rabbit;
        this.json = json;
        this.registry = registry;
        this.database = database;
        this.stops = stops;
        this.confirmTimeoutMillis = confirmTimeoutMillis;
    }

    @RabbitListener(
        id = PARSE_LISTENER,
        queues = RabbitMqConfig.DLQ_DOCUMENT_PARSE,
        containerFactory = CONTAINER_FACTORY,
        ackMode = "MANUAL",
        autoStartup = "${lexflow.delivery.consumer-enabled:true}"
    )
    public void parse(Message message, Channel channel) {
        collect(PARSE_LISTENER, RabbitMqConfig.QUEUE_DOCUMENT_PARSE, message, channel);
    }

    @RabbitListener(
        id = INDEX_LISTENER,
        queues = RabbitMqConfig.DLQ_KNOWLEDGE_INGEST,
        containerFactory = CONTAINER_FACTORY,
        ackMode = "MANUAL",
        autoStartup = "${lexflow.delivery.consumer-enabled:true}"
    )
    public void index(Message message, Channel channel) {
        collect(INDEX_LISTENER, RabbitMqConfig.QUEUE_KNOWLEDGE_INGEST, message, channel);
    }

    @RabbitListener(
        id = CONTRACT_LISTENER,
        queues = RabbitMqConfig.DLQ_CONTRACT_REVIEW,
        containerFactory = CONTAINER_FACTORY,
        ackMode = "MANUAL",
        autoStartup = "${lexflow.delivery.consumer-enabled:true}"
    )
    public void contract(Message message, Channel channel) {
        collect(CONTRACT_LISTENER, RabbitMqConfig.QUEUE_CONTRACT_REVIEW, message, channel);
    }

    @RabbitListener(
        id = GENERATION_LISTENER,
        queues = RabbitMqConfig.DLQ_DOCUMENT_GENERATE,
        containerFactory = CONTAINER_FACTORY,
        ackMode = "MANUAL",
        autoStartup = "${lexflow.delivery.consumer-enabled:true}"
    )
    public void generation(Message message, Channel channel) {
        collect(GENERATION_LISTENER, RabbitMqConfig.QUEUE_DOCUMENT_GENERATE, message, channel);
    }

    @RabbitListener(
        id = ANALYSIS_LISTENER,
        queues = RabbitMqConfig.DLQ_CASE_ANALYSIS,
        containerFactory = CONTAINER_FACTORY,
        ackMode = "MANUAL",
        autoStartup = "${lexflow.delivery.consumer-enabled:true}"
    )
    public void analysis(Message message, Channel channel) {
        collect(ANALYSIS_LISTENER, RabbitMqConfig.QUEUE_CASE_ANALYSIS, message, channel);
    }

    private void collect(String listener, String queue, Message message, Channel channel) {
        if (closed || paused.containsKey(listener)) {
            return;
        }
        try {
            JsonNode event;
            try {
                event = event(message, queue);
            } catch (InvalidEvent failure) {
                // 只有隔离队列确认接收后才 ACK 原消息，保留无法解释的事件以供人工核查。
                quarantine(message, queue, "INVALID_EVENT");
                channel.basicAck(message.getMessageProperties().getDeliveryTag(), false);
                return;
            }
            var headers = message.getMessageProperties().getHeaders();
            Object kind = headers.get("x-lexflow-failure-kind");
            String failureKind = kind instanceof String value ? value : null;
            if (
                !recovery.acceptDeadLetter(
                    queue,
                    event,
                    retryCount(headers.get("x-lexflow-delivery-retry")),
                    failureKind
                )
            ) {
                quarantine(message, queue, "UNBOUND_EVENT");
            }
            channel.basicAck(message.getMessageProperties().getDeliveryTag(), false);
        } catch (Exception failure) {
            if (failure instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            // 保留未确认消息并停容器；通道关闭后由 broker 重投，日志不记录载荷、头或异常文本。
            pause(listener);
        }
    }

    private JsonNode event(Message message, String queue) throws InvalidEvent {
        try {
            if (message.getBody().length > MAX_BYTES) {
                throw new InvalidEvent();
            }
            var reader = json
                .reader()
                .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .with(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
            JsonNode node = reader.readTree(message.getBody());
            // 兼容历史上再次 JSON 编码的消息，但最多解包两层，避免无限嵌套。
            for (int layer = 0; node != null && node.isTextual() && layer < 2; layer++) {
                node = reader.readTree(node.textValue());
            }
            String type = switch (queue) {
                case RabbitMqConfig.QUEUE_DOCUMENT_PARSE -> "DOCUMENT_PARSE";
                case RabbitMqConfig.QUEUE_KNOWLEDGE_INGEST -> "KNOWLEDGE_INGEST";
                case RabbitMqConfig.QUEUE_CONTRACT_REVIEW -> "CONTRACT_REVIEW";
                case RabbitMqConfig.QUEUE_DOCUMENT_GENERATE -> "DOCUMENT_GENERATE";
                case RabbitMqConfig.QUEUE_CASE_ANALYSIS -> "CASE_ANALYSIS";
                default -> throw new InvalidEvent();
            };
            boolean legacyType = Set.of(
                "DOCUMENT_PARSE",
                "CONTRACT_REVIEW",
                "DOCUMENT_GENERATE"
            ).contains(type);
            boolean workflow = Set.of(
                "CONTRACT_REVIEW",
                "DOCUMENT_GENERATE",
                "CASE_ANALYSIS"
            ).contains(type);
            boolean validType =
                node != null &&
                ((legacyType && !node.has("type")) ||
                    (node.path("type").isTextual() && type.equals(node.path("type").asText())));
            if (
                node == null ||
                !node.isObject() ||
                !node.path("schemaVersion").isTextual() ||
                !"1.0".equals(node.path("schemaVersion").asText()) ||
                !validType ||
                !decimal(node.path("tenantId")) ||
                !decimal(node.path("taskId")) ||
                !decimal(node.path("resourceVersionId")) ||
                !node.path("attempt").isInt() ||
                node.path("attempt").intValue() < 0 ||
                (workflow && !node.has("stateVersion")) ||
                (node.has("stateVersion") &&
                    (!node.path("stateVersion").isInt() ||
                        node.path("stateVersion").intValue() < 1))
            ) {
                throw new InvalidEvent();
            }
            return node;
        } catch (InvalidEvent failure) {
            throw failure;
        } catch (Exception failure) {
            throw new InvalidEvent();
        }
    }

    private static boolean decimal(JsonNode node) {
        if (!node.isTextual() || !node.textValue().matches("[1-9][0-9]{0,18}")) {
            return false;
        }
        try {
            return Long.parseLong(node.textValue()) > 0;
        } catch (NumberFormatException failure) {
            return false;
        }
    }

    /** 缺失重试头视为 0；无效或超界值按上限 3 处理，防止异常值增加自动重试预算。 */
    private static int retryCount(Object value) {
        if (value == null) {
            return 0;
        }
        if (
            !(
                value instanceof Byte ||
                value instanceof Short ||
                value instanceof Integer ||
                value instanceof Long ||
                value instanceof BigInteger
            )
        ) {
            return 3;
        }
        var number = new BigInteger(value.toString());
        return number.signum() >= 0 && number.compareTo(BigInteger.valueOf(3)) <= 0
            ? number.intValue()
            : 3;
    }

    private void quarantine(Message original, String queue, String reason) throws Exception {
        var properties = new MessageProperties();
        properties.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
        properties.setContentType(original.getMessageProperties().getContentType());
        properties.setContentEncoding(original.getMessageProperties().getContentEncoding());
        properties.setMessageId(UUID.randomUUID().toString());
        properties.setHeader("x-lexflow-quarantine-source-queue", queue);
        properties.setHeader("x-lexflow-quarantine-reason", reason);
        var copy = new Message(
            Arrays.copyOf(original.getBody(), original.getBody().length),
            properties
        );
        if (!Boolean.TRUE.equals(rabbit.isMandatoryFor(copy))) {
            throw new IllegalStateException("Mandatory publication required");
        }
        var correlation = new CorrelationData(properties.getMessageId());
        rabbit.send("", RabbitMqConfig.QUEUE_TASK_DELIVERY_QUARANTINE, copy, correlation);
        var confirmation = correlation.getFuture().get(confirmTimeoutMillis, TimeUnit.MILLISECONDS);
        if (confirmation == null || !confirmation.isAck() || correlation.getReturned() != null) {
            throw new IllegalStateException("Quarantine publication not confirmed");
        }
    }

    private void pause(String listener) {
        var state = new Pause();
        var existing = paused.putIfAbsent(listener, state);
        if (existing != null) {
            return;
        }
        log.warn(
            "Paused dead-letter listener {} until its container stops and the database is healthy",
            listener
        );
        stop(listener, state);
    }

    private void stop(String listener, Pause state) {
        synchronized (state) {
            if (closed || state.stopping || state.stopped) {
                return;
            }
            state.stopping = true;
        }
        try {
            stops.execute(() -> {
                try {
                    var container = registry.getListenerContainer(listener);
                    if (container == null) {
                        state.stopping = false;
                        return;
                    }
                    // 在独立线程停容器，避免消费者等待自身退出；回调确认通道关闭后才允许重启。
                    container.stop(() -> {
                        state.stopped = true;
                        state.stopping = false;
                    });
                } catch (Exception failure) {
                    state.stopping = false;
                    log.warn(
                        "Could not stop owned dead-letter listener {}; it remains paused",
                        listener
                    );
                }
            });
        } catch (RuntimeException failure) {
            state.stopping = false;
        }
    }

    @Scheduled(fixedDelay = 15000, initialDelay = 15000)
    public void restartHealthy() {
        if (closed) {
            return;
        }
        for (var entry : paused.entrySet()) {
            String listener = entry.getKey();
            Pause state = entry.getValue();
            if (!state.stopped) {
                stop(listener, state);
                continue;
            }
            MessageListenerContainer container = registry.getListenerContainer(listener);
            if (container == null || container.isRunning()) {
                continue;
            }
            try {
                if (
                    !Integer.valueOf(1).equals(database.queryForObject("SELECT 1", Integer.class))
                ) {
                    continue;
                }
                if (closed || !paused.remove(listener, state)) {
                    continue;
                }
                try {
                    container.start();
                    log.info(
                        "Restarted owned dead-letter listener {} after database health check",
                        listener
                    );
                } catch (Exception failure) {
                    state.stopped = false;
                    var existing = paused.putIfAbsent(listener, state);
                    stop(listener, existing == null ? state : existing);
                }
            } catch (Exception failure) {
                // 下一轮定时检查重试数据库健康探测，原消息的重投由关闭通道后的 broker 负责。
            }
        }
    }

    @PreDestroy
    public void close() {
        closed = true;
        stops.shutdownNow();
    }

    private static final class InvalidEvent extends Exception {}

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(name = "scheduling.enabled", havingValue = "true", matchIfMissing = true)
    static class OwnedContainers {

        @Bean(name = CONTAINER_FACTORY)
        SimpleRabbitListenerContainerFactory taskDeadLetterContainerFactory(
            ConnectionFactory connectionFactory
        ) {
            var factory = new SimpleRabbitListenerContainerFactory();
            factory.setConnectionFactory(connectionFactory);
            factory.setAcknowledgeMode(AcknowledgeMode.MANUAL);
            // 每个死信容器最多持有一条未确认消息；故障停机时关闭通道，把原消息交回 broker。
            factory.setConcurrentConsumers(1);
            factory.setMaxConcurrentConsumers(1);
            factory.setPrefetchCount(1);
            factory.setForceStop(true);
            factory.setContainerCustomizer(container -> container.setShutdownTimeout(1000));
            return factory;
        }
    }
}
