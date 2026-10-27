package com.lexflow.website.core.mq;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lexflow.website.biz.TaskDeliveryRecoveryBiz;
import com.lexflow.website.core.config.RabbitMqConfig;
import com.lexflow.website.core.mq.TaskDeadLetterCollector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.amqp.core.AcknowledgeMode;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import org.springframework.amqp.rabbit.listener.api.ChannelAwareMessageListener;
import org.springframework.jdbc.core.JdbcTemplate;

/** Broker/channel-close evidence in a unique disposable vhost; business DB transitions are tested separately. */
@EnabledIfEnvironmentVariable(named = "LEXFLOW_LOCAL_INTEGRATION", matches = "true")
class TaskDeadLetterCollectorIntegrationTest {

    private static final String QUEUE = RabbitMqConfig.DLQ_DOCUMENT_PARSE;

    @Test
    void databaseFailureStopsItsConsumerAndRetainsOriginalForHealthCheckedRecovery()
        throws Exception {
        try (var fixture = new Fixture(true)) {
            var healthy = new AtomicBoolean(false);
            var accepted = new AtomicBoolean(false);
            var attempts = new AtomicInteger();
            when(fixture.database.queryForObject("SELECT 1", Integer.class)).thenAnswer(
                invocation -> {
                    if (!healthy.get()) throw new IllegalStateException(
                        "synthetic database outage"
                    );
                    return 1;
                }
            );
            when(
                fixture.recovery.acceptDeadLetter(
                    eq(RabbitMqConfig.QUEUE_DOCUMENT_PARSE),
                    any(JsonNode.class),
                    eq(0),
                    isNull()
                )
            ).thenAnswer(invocation -> {
                attempts.incrementAndGet();
                if (!accepted.get()) throw new IllegalStateException("synthetic database outage");
                return true;
            });
            byte[] original =
                "{\"schemaVersion\":\"1.0\",\"tenantId\":\"101\",\"taskId\":\"202\",\"resourceVersionId\":\"303\",\"attempt\":0}".getBytes(
                    StandardCharsets.UTF_8
                ); // Historical parse event with no type/stateVersion.
            fixture.publish(original);
            fixture.start();
            await(
                () ->
                    !fixture.container.isRunning() &&
                    fixture.container.getActiveConsumerCount() == 0 &&
                    fixture.ready(QUEUE) == 1,
                "DB failure must return the unacknowledged original to its DLQ"
            );
            assertEquals(1, attempts.get());
            fixture.collector.restartHealthy();
            assertFalse(fixture.container.isRunning());
            assertEquals(1, fixture.ready(QUEUE));
            Thread.sleep(150);
            assertEquals(
                1,
                attempts.get(),
                "A stopped consumer must not hot-loop the failing message"
            );
            healthy.set(true);
            accepted.set(true);
            await(() -> {
                fixture.collector.restartHealthy();
                return attempts.get() == 2 && fixture.ready(QUEUE) == 0;
            }, "Healthy recovery must accept and acknowledge the retained original");
            assertTrue(fixture.container.isRunning());
            assertEquals(0, fixture.ready(RabbitMqConfig.QUEUE_TASK_DELIVERY_QUARANTINE));
            verify(fixture.recovery, times(2)).acceptDeadLetter(
                eq(RabbitMqConfig.QUEUE_DOCUMENT_PARSE),
                argThat(value -> !value.has("type") && value.path("taskId").asText().equals("202")),
                eq(0),
                isNull()
            );
            fixture.verifySourceAcknowledgedAfterChannelClose();
        }
    }

    @Test
    void corruptBinaryBytesAreDurablyQuarantinedBeforeOriginalDisappears() throws Exception {
        try (var fixture = new Fixture(true)) {
            byte[] original = new byte[] { (byte) 0xff, 0, 0x7f, 0x7b, 0x01, 0x7d };
            fixture.publish(original);
            fixture.start();
            await(
                () ->
                    fixture.ready(QUEUE) == 0 &&
                    fixture.ready(RabbitMqConfig.QUEUE_TASK_DELIVERY_QUARANTINE) == 1,
                "Corrupt bytes must have a confirmed quarantine copy before source acknowledgement"
            );
            var quarantined = fixture.rabbit.receive(
                RabbitMqConfig.QUEUE_TASK_DELIVERY_QUARANTINE,
                1000
            );
            assertNotNull(quarantined);
            assertArrayEquals(original, quarantined.getBody());
            // Received broker metadata is separate from properties used for a subsequent publication.
            assertEquals(
                MessageDeliveryMode.PERSISTENT,
                quarantined.getMessageProperties().getReceivedDeliveryMode()
            );
            assertEquals(
                RabbitMqConfig.QUEUE_DOCUMENT_PARSE,
                quarantined.getMessageProperties().getHeader("x-lexflow-quarantine-source-queue")
            );
            assertEquals(
                "INVALID_EVENT",
                quarantined.getMessageProperties().getHeader("x-lexflow-quarantine-reason")
            );
            assertTrue(fixture.container.isRunning());
            verifyNoInteractions(fixture.recovery, fixture.database);
            fixture.verifySourceAcknowledgedAfterChannelClose();
        }
    }

    @Test
    void mandatoryReturnRetainsSourceUntilQuarantineExistsAndOwnedConsumerRestarts()
        throws Exception {
        try (var fixture = new Fixture(false)) {
            when(fixture.database.queryForObject("SELECT 1", Integer.class)).thenReturn(1);
            byte[] original = "{only a synthetic corrupt event".getBytes(StandardCharsets.UTF_8);
            fixture.publish(original);
            fixture.start();
            await(
                () ->
                    !fixture.container.isRunning() &&
                    fixture.container.getActiveConsumerCount() == 0 &&
                    fixture.ready(QUEUE) == 1,
                "A mandatory return must pause and retain its source message"
            );
            assertNull(
                fixture.admin.getQueueProperties(RabbitMqConfig.QUEUE_TASK_DELIVERY_QUARANTINE)
            );
            fixture.declareQuarantine();
            await(() -> {
                fixture.collector.restartHealthy();
                return (
                    fixture.ready(QUEUE) == 0 &&
                    fixture.ready(RabbitMqConfig.QUEUE_TASK_DELIVERY_QUARANTINE) == 1
                );
            }, "After declaring quarantine, restart must preserve the bytes and acknowledge its source");
            var quarantined = fixture.rabbit.receive(
                RabbitMqConfig.QUEUE_TASK_DELIVERY_QUARANTINE,
                1000
            );
            assertNotNull(quarantined);
            assertArrayEquals(original, quarantined.getBody());
            assertEquals(
                MessageDeliveryMode.PERSISTENT,
                quarantined.getMessageProperties().getReceivedDeliveryMode()
            );
            assertTrue(fixture.container.isRunning());
            verifyNoInteractions(fixture.recovery);
            fixture.verifySourceAcknowledgedAfterChannelClose();
        }
    }

    @FunctionalInterface
    private interface CheckedCondition {
        boolean get() throws Exception;
    }

    private static void await(CheckedCondition condition, String message) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.get()) return;
            Thread.sleep(25);
        }
        assertTrue(condition.get(), message);
    }

    private static final class Fixture implements AutoCloseable {

        final String vhost = "lexflow-java-dead-letter-test-" + UUID.randomUUID();
        final String base = "http://127.0.0.1:15673/api/vhosts/" + vhost;
        final String auth =
            "Basic " +
            Base64.getEncoder().encodeToString(
                "lexflow:lexflow_mq_dev_only".getBytes(StandardCharsets.UTF_8)
            );
        final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();
        final CachingConnectionFactory connection = new CachingConnectionFactory(
            "127.0.0.1",
            15672
        );
        final TaskDeliveryRecoveryBiz recovery = mock(TaskDeliveryRecoveryBiz.class);
        final JdbcTemplate database = mock(JdbcTemplate.class);
        final RabbitListenerEndpointRegistry registry = mock(RabbitListenerEndpointRegistry.class);
        final RabbitTemplate rabbit;
        final RabbitAdmin admin;
        final SimpleMessageListenerContainer container;
        final TaskDeadLetterCollector collector;
        boolean created;

        Fixture(boolean quarantine) throws Exception {
            connection.setUsername("lexflow");
            connection.setPassword("lexflow_mq_dev_only");
            connection.setVirtualHost(vhost);
            connection.setConnectionTimeout(5000);
            connection.setPublisherConfirmType(CachingConnectionFactory.ConfirmType.CORRELATED);
            connection.setPublisherReturns(true);
            rabbit = new RabbitTemplate(connection);
            rabbit.setMandatory(true);
            admin = new RabbitAdmin(connection);
            container = new SimpleMessageListenerContainer(connection);
            container.setListenerId(TaskDeadLetterCollector.PARSE_LISTENER);
            container.setQueueNames(QUEUE);
            container.setAcknowledgeMode(AcknowledgeMode.MANUAL);
            container.setConcurrentConsumers(1);
            container.setMaxConcurrentConsumers(1);
            container.setPrefetchCount(1);
            container.setForceStop(true);
            container.setShutdownTimeout(1000);
            container.setAutoStartup(false);
            container.setMissingQueuesFatal(true);
            container.setConsumerStartTimeout(5000);
            when(registry.getListenerContainer(TaskDeadLetterCollector.PARSE_LISTENER)).thenReturn(
                container
            );
            collector = new TaskDeadLetterCollector(
                recovery,
                rabbit,
                new ObjectMapper(),
                registry,
                database
            );
            container.setMessageListener((ChannelAwareMessageListener) collector::parse);
            try {
                assertEquals(404, call(base, "GET", null).statusCode());
                assertEquals(201, call(base, "PUT", "{}").statusCode());
                created = true;
                assertTrue(
                    Set.of(201, 204).contains(
                        call(
                            "http://127.0.0.1:15673/api/permissions/" + vhost + "/lexflow",
                            "PUT",
                            "{\"configure\":\".*\",\"write\":\".*\",\"read\":\".*\"}"
                        ).statusCode()
                    )
                );
                admin.declareQueue(new Queue(QUEUE, true));
                if (quarantine) declareQuarantine();
                container.afterPropertiesSet();
            } catch (Exception | AssertionError failure) {
                try {
                    close();
                } catch (Exception | AssertionError cleanupFailure) {
                    failure.addSuppressed(cleanupFailure);
                }
                throw failure;
            }
        }

        void declareQuarantine() {
            admin.declareQueue(new RabbitMqConfig().taskDeliveryQuarantine());
        }

        void start() {
            container.start();
        }

        void verifySourceAcknowledgedAfterChannelClose() throws Exception {
            container.stop();
            await(
                () -> container.getActiveConsumerCount() == 0 && ready(QUEUE) == 0,
                "Closing the consumer must not return an unacknowledged original to the source DLQ"
            );
        }

        int ready(String queue) {
            var properties = admin.getQueueProperties(queue);
            assertNotNull(properties, "Owned test queue must exist: " + queue);
            return ((Number) properties.get(RabbitAdmin.QUEUE_MESSAGE_COUNT)).intValue();
        }

        void publish(byte[] bytes) throws Exception {
            var properties = new MessageProperties();
            properties.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
            properties.setContentType("application/json");
            var correlation = new CorrelationData(UUID.randomUUID().toString());
            rabbit.send("", QUEUE, new Message(bytes, properties), correlation);
            assertTrue(
                correlation.getFuture().get(5, java.util.concurrent.TimeUnit.SECONDS).isAck()
            );
            assertNull(correlation.getReturned());
        }

        HttpResponse<String> call(String url, String method, String body) throws Exception {
            var request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(5))
                .header("Authorization", auth)
                .header("Content-Type", "application/json")
                .method(
                    method,
                    body == null
                        ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(body)
                )
                .build();
            return http.send(request, HttpResponse.BodyHandlers.ofString());
        }

        @Override
        public void close() throws Exception {
            try {
                collector.close();
                container.stop();
                container.destroy();
            } finally {
                try {
                    connection.destroy();
                } finally {
                    if (created) {
                        assertEquals(204, call(base, "DELETE", null).statusCode());
                        assertEquals(404, call(base, "GET", null).statusCode());
                        created = false;
                    }
                }
            }
        }
    }
}
