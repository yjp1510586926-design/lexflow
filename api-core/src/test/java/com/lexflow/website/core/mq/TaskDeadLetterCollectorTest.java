package com.lexflow.website.core.mq;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lexflow.website.biz.TaskDeliveryRecoveryBiz;
import com.lexflow.website.core.config.RabbitMqConfig;
import com.rabbitmq.client.Channel;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.AcknowledgeMode;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.core.ReturnedMessage;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerEndpoint;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.listener.MessageListenerContainer;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.springframework.jdbc.core.JdbcTemplate;

class TaskDeadLetterCollectorTest {

    private final ObjectMapper json = new ObjectMapper();
    private TaskDeliveryRecoveryBiz recovery;
    private RabbitTemplate rabbit;
    private RabbitListenerEndpointRegistry registry;
    private JdbcTemplate database;
    private Channel channel;
    private MessageListenerContainer parseContainer;
    private MessageListenerContainer indexContainer;
    private QueuedExecutor stops;
    private TaskDeadLetterCollector collector;

    @BeforeEach
    void setup() {
        recovery = mock(TaskDeliveryRecoveryBiz.class);
        rabbit = mock(RabbitTemplate.class);
        registry = mock(RabbitListenerEndpointRegistry.class);
        database = mock(JdbcTemplate.class);
        channel = mock(Channel.class);
        parseContainer = mock(MessageListenerContainer.class);
        indexContainer = mock(MessageListenerContainer.class);
        when(registry.getListenerContainer(TaskDeadLetterCollector.PARSE_LISTENER)).thenReturn(
            parseContainer
        );
        when(registry.getListenerContainer(TaskDeadLetterCollector.INDEX_LISTENER)).thenReturn(
            indexContainer
        );
        stops = new QueuedExecutor();
        collector = new TaskDeadLetterCollector(
            recovery,
            rabbit,
            json,
            registry,
            database,
            stops,
            10
        );
    }

    @AfterEach
    void cleanup() {
        collector.close();
    }

    private Message message(String content) {
        var properties = new MessageProperties();
        properties.setDeliveryTag(17);
        properties.setContentType("application/json");
        return new Message(content.getBytes(StandardCharsets.UTF_8), properties);
    }

    private String event(String type) {
        return (
            "{\"schemaVersion\":\"1.0\",\"type\":\"" +
            type +
            "\",\"tenantId\":\"101\"," +
            "\"taskId\":\"202\",\"resourceVersionId\":\"303\",\"attempt\":0}"
        );
    }

    private void confirmQuarantine(boolean acknowledged, boolean returned) {
        when(rabbit.isMandatoryFor(any(Message.class))).thenReturn(true);
        doAnswer(invocation -> {
            CorrelationData correlation = invocation.getArgument(3);
            if (returned) correlation.setReturned(
                new ReturnedMessage(
                    invocation.getArgument(2),
                    312,
                    "NO_ROUTE",
                    "",
                    RabbitMqConfig.QUEUE_TASK_DELIVERY_QUARANTINE
                )
            );
            correlation.getFuture().complete(new CorrelationData.Confirm(acknowledged, null));
            return null;
        })
            .when(rabbit)
            .send(
                eq(""),
                eq(RabbitMqConfig.QUEUE_TASK_DELIVERY_QUARANTINE),
                any(Message.class),
                any(CorrelationData.class)
            );
    }

    private void assertNoAcknowledgement() throws IOException {
        verify(channel, never()).basicAck(anyLong(), anyBoolean());
        verify(channel, never()).basicNack(anyLong(), anyBoolean(), anyBoolean());
        verify(channel, never()).basicReject(anyLong(), anyBoolean());
    }

    @Test
    void allPaidWorkflowsRequireVersionAndUseTheirOwnDurableIntake() throws Exception {
        String[] types = { "CONTRACT_REVIEW", "DOCUMENT_GENERATE", "CASE_ANALYSIS" };
        String[] queues = {
            RabbitMqConfig.QUEUE_CONTRACT_REVIEW,
            RabbitMqConfig.QUEUE_DOCUMENT_GENERATE,
            RabbitMqConfig.QUEUE_CASE_ANALYSIS,
        };
        for (int i = 0; i < types.length; i++) {
            clearInvocations(recovery, channel, rabbit);
            when(
                recovery.acceptDeadLetter(eq(queues[i]), any(JsonNode.class), eq(0), isNull())
            ).thenReturn(true);
            var value = message(
                event(types[i]).replace("\"attempt\":0", "\"attempt\":0,\"stateVersion\":4")
            );
            paidCollect(i, value);
            var order = inOrder(recovery, channel);
            order
                .verify(recovery)
                .acceptDeadLetter(
                    eq(queues[i]),
                    argThat(e -> e.path("stateVersion").asInt() == 4),
                    eq(0),
                    isNull()
                );
            order.verify(channel).basicAck(17, false);
            verifyNoInteractions(rabbit);
            clearInvocations(recovery, channel);
            confirmQuarantine(true, false);
            paidCollect(i, message(event(types[i])));
            verifyNoInteractions(recovery);
            verify(channel).basicAck(17, false);
        }
    }

    private void paidCollect(int type, Message message) {
        switch (type) {
            case 0 -> collector.contract(message, channel);
            case 1 -> collector.generation(message, channel);
            case 2 -> collector.analysis(message, channel);
            default -> throw new IllegalArgumentException();
        }
    }

    @Test
    void paidWorkflowsAcceptLegacyMissingTypeOnlyAfterBindingAndPauseOnlyOwnedListener()
        throws Exception {
        String[] types = { "CONTRACT_REVIEW", "DOCUMENT_GENERATE", "CASE_ANALYSIS" };
        String[] queues = {
            RabbitMqConfig.QUEUE_CONTRACT_REVIEW,
            RabbitMqConfig.QUEUE_DOCUMENT_GENERATE,
            RabbitMqConfig.QUEUE_CASE_ANALYSIS,
        };
        String[] listeners = {
            TaskDeadLetterCollector.CONTRACT_LISTENER,
            TaskDeadLetterCollector.GENERATION_LISTENER,
            TaskDeadLetterCollector.ANALYSIS_LISTENER,
        };
        for (int i = 0; i < 3; i++) {
            var own = mock(MessageListenerContainer.class);
            when(registry.getListenerContainer(listeners[i])).thenReturn(own);
            clearInvocations(recovery, channel, rabbit);
            var raw = event(types[i])
                .replace("\"attempt\":0", "\"attempt\":0,\"stateVersion\":4")
                .replace("\"type\":\"" + types[i] + "\",", "");
            if (i < 2) {
                when(
                    recovery.acceptDeadLetter(eq(queues[i]), any(JsonNode.class), eq(0), isNull())
                ).thenReturn(true);
                paidCollect(i, message(raw));
                verify(recovery).acceptDeadLetter(
                    eq(queues[i]),
                    argThat(e -> !e.has("type")),
                    eq(0),
                    isNull()
                );
                verify(channel).basicAck(17, false);
            } else {
                confirmQuarantine(true, false);
                paidCollect(i, message(raw));
                verifyNoInteractions(recovery);
                verify(channel).basicAck(17, false);
            }
            clearInvocations(channel);
            when(
                recovery.acceptDeadLetter(
                    eq(queues[i]),
                    any(JsonNode.class),
                    anyInt(),
                    nullable(String.class)
                )
            ).thenThrow(new IllegalStateException("database offline"));
            paidCollect(
                i,
                message(
                    event(types[i]).replace("\"attempt\":0", "\"attempt\":0,\"stateVersion\":4")
                )
            );
            assertNoAcknowledgement();
            stops.runNext();
            verify(own).stop(any(Runnable.class));
            verifyNoInteractions(parseContainer, indexContainer);
        }
    }

    @Test
    void ownedDeadLetterCommitsBeforeAckAndDoesNotPublishOrStop() throws Exception {
        var original = message(event("DOCUMENT_PARSE"));
        original.getMessageProperties().setHeader("x-lexflow-delivery-retry", 3L);
        original.getMessageProperties().setHeader("x-lexflow-failure-kind", "PERMANENT");
        when(
            recovery.acceptDeadLetter(
                eq(RabbitMqConfig.QUEUE_DOCUMENT_PARSE),
                any(JsonNode.class),
                eq(3),
                eq("PERMANENT")
            )
        ).thenReturn(true);
        collector.parse(original, channel);
        var order = inOrder(recovery, channel);
        order
            .verify(recovery)
            .acceptDeadLetter(
                eq(RabbitMqConfig.QUEUE_DOCUMENT_PARSE),
                any(JsonNode.class),
                eq(3),
                eq("PERMANENT")
            );
        order.verify(channel).basicAck(17, false);
        verifyNoInteractions(rabbit, database, parseContainer, indexContainer);
        assertEquals(0, stops.queued());
    }

    @Test
    void legacyAndTwoStringLayersUnwrapAndUnknownFailureIsPassedWithoutDowngrade()
        throws Exception {
        String content = event("KNOWLEDGE_INGEST");
        content = json.writeValueAsString(json.writeValueAsString(content));
        var original = message(content);
        original.getMessageProperties().setHeader("x-lexflow-delivery-retry", "invalid");
        original.getMessageProperties().setHeader("x-lexflow-failure-kind", "NEW_OR_UNKNOWN_KIND");
        when(
            recovery.acceptDeadLetter(
                eq(RabbitMqConfig.QUEUE_KNOWLEDGE_INGEST),
                any(JsonNode.class),
                eq(3),
                eq("NEW_OR_UNKNOWN_KIND")
            )
        ).thenReturn(true);
        collector.index(original, channel);
        verify(recovery).acceptDeadLetter(
            eq(RabbitMqConfig.QUEUE_KNOWLEDGE_INGEST),
            argThat(
                value ->
                    value.isObject() &&
                    value.path("taskId").asText().equals("202") &&
                    !value.has("stateVersion")
            ),
            eq(3),
            eq("NEW_OR_UNKNOWN_KIND")
        );
        verify(channel).basicAck(17, false);
        verifyNoInteractions(rabbit);
    }

    @Test
    void absentFailureClassificationIsNotInventedAsTransient() throws Exception {
        when(
            recovery.acceptDeadLetter(
                eq(RabbitMqConfig.QUEUE_KNOWLEDGE_INGEST),
                any(JsonNode.class),
                eq(0),
                isNull()
            )
        ).thenReturn(true);
        collector.index(message(event("KNOWLEDGE_INGEST")), channel);
        verify(recovery).acceptDeadLetter(
            eq(RabbitMqConfig.QUEUE_KNOWLEDGE_INGEST),
            any(JsonNode.class),
            eq(0),
            isNull()
        );
        verify(channel).basicAck(17, false);
    }

    @Test
    void historicalMissingTypeIsAllowedOnlyForParseAndStillNeedsDatabaseBinding() throws Exception {
        var parse = message(event("DOCUMENT_PARSE").replace("\"type\":\"DOCUMENT_PARSE\",", ""));
        when(
            recovery.acceptDeadLetter(
                eq(RabbitMqConfig.QUEUE_DOCUMENT_PARSE),
                any(JsonNode.class),
                eq(0),
                isNull()
            )
        ).thenReturn(true);
        collector.parse(parse, channel);
        verify(recovery).acceptDeadLetter(
            eq(RabbitMqConfig.QUEUE_DOCUMENT_PARSE),
            argThat(value -> !value.has("type")),
            eq(0),
            isNull()
        );
        verify(channel).basicAck(17, false);
        clearInvocations(channel);
        confirmQuarantine(true, false);
        collector.index(
            message(event("KNOWLEDGE_INGEST").replace("\"type\":\"KNOWLEDGE_INGEST\",", "")),
            channel
        );
        verify(recovery, never()).acceptDeadLetter(
            eq(RabbitMqConfig.QUEUE_KNOWLEDGE_INGEST),
            any(JsonNode.class),
            anyInt(),
            nullable(String.class)
        );
        verify(rabbit).send(
            eq(""),
            eq(RabbitMqConfig.QUEUE_TASK_DELIVERY_QUARANTINE),
            any(Message.class),
            any(CorrelationData.class)
        );
        verify(channel).basicAck(17, false);
    }

    @Test
    void corruptOrMismatchedEventsPreserveExactBytesInPersistentQuarantineBeforeAck()
        throws Exception {
        var valid = event("DOCUMENT_PARSE");
        var malformed = List.of(
            "{invalid",
            "null",
            "[]",
            valid + " {}",
            event("KNOWLEDGE_INGEST"),
            valid.replace("\"1.0\"", "\"2.0\""),
            valid.replace("\"1.0\"", "1.0"),
            valid.replace("\"101\"", "101"),
            valid.replace("\"202\"", "\"9223372036854775808\""),
            valid.replace("\"303\"", "\"0\""),
            valid.replace("\"attempt\":0", "\"attempt\":-1"),
            valid.replace("\"attempt\":0", "\"attempt\":1.5"),
            valid.replace("\"attempt\":0", "\"attempt\":0,\"stateVersion\":0"),
            valid.replace("\"attempt\":0", "\"attempt\":0,\"attempt\":1"),
            "x".repeat(8193),
            json.writeValueAsString(json.writeValueAsString(json.writeValueAsString(valid)))
        );
        confirmQuarantine(true, false);
        for (String content : malformed) {
            clearInvocations(rabbit, channel);
            var original = message(content);
            collector.parse(original, channel);
            var sent = org.mockito.ArgumentCaptor.forClass(Message.class);
            var order = inOrder(rabbit, channel);
            order.verify(rabbit).isMandatoryFor(any(Message.class));
            order
                .verify(rabbit)
                .send(
                    eq(""),
                    eq(RabbitMqConfig.QUEUE_TASK_DELIVERY_QUARANTINE),
                    sent.capture(),
                    any(CorrelationData.class)
                );
            order.verify(channel).basicAck(17, false);
            assertArrayEquals(original.getBody(), sent.getValue().getBody());
            assertEquals(
                MessageDeliveryMode.PERSISTENT,
                sent.getValue().getMessageProperties().getDeliveryMode()
            );
            assertEquals(
                RabbitMqConfig.QUEUE_DOCUMENT_PARSE,
                sent
                    .getValue()
                    .getMessageProperties()
                    .getHeader("x-lexflow-quarantine-source-queue")
            );
            assertEquals(
                "INVALID_EVENT",
                sent.getValue().getMessageProperties().getHeader("x-lexflow-quarantine-reason")
            );
            verify(channel, never()).basicNack(anyLong(), anyBoolean(), anyBoolean());
        }
        verifyNoInteractions(recovery, database);
        assertEquals(0, stops.queued());
    }

    @Test
    void unboundDatabaseEventIsQuarantinedNotAcceptedAsARealTask() throws Exception {
        when(
            recovery.acceptDeadLetter(
                eq(RabbitMqConfig.QUEUE_DOCUMENT_PARSE),
                any(JsonNode.class),
                eq(0),
                isNull()
            )
        ).thenReturn(false);
        confirmQuarantine(true, false);
        collector.parse(message(event("DOCUMENT_PARSE")), channel);
        var sent = org.mockito.ArgumentCaptor.forClass(Message.class);
        verify(rabbit).send(
            eq(""),
            eq(RabbitMqConfig.QUEUE_TASK_DELIVERY_QUARANTINE),
            sent.capture(),
            any(CorrelationData.class)
        );
        assertEquals(
            "UNBOUND_EVENT",
            sent.getValue().getMessageProperties().getHeader("x-lexflow-quarantine-reason")
        );
        verify(channel).basicAck(17, false);
    }

    @Test
    void returnedPublicationRetainsOriginalAndStopsOnlyItsOwnedContainerAsynchronously()
        throws Exception {
        confirmQuarantine(true, true);
        collector.parse(message("invalid"), channel);
        assertNoAcknowledgement();
        verifyNoInteractions(parseContainer, indexContainer);
        assertEquals(1, stops.queued());
        stops.runNext();
        verify(parseContainer).stop(any(Runnable.class));
        verifyNoInteractions(indexContainer);
    }

    @Test
    void nackConfirmationCannotAcknowledgeTheOriginal() throws Exception {
        confirmQuarantine(false, false);
        collector.index(message("invalid"), channel);
        assertNoAcknowledgement();
        stops.runNext();
        verify(indexContainer).stop(any(Runnable.class));
        verifyNoInteractions(parseContainer);
    }

    @Test
    void confirmTimeoutNeverDiscardsEitherOriginal() throws Exception {
        when(rabbit.isMandatoryFor(any(Message.class))).thenReturn(true);
        collector.parse(message("invalid"), channel); // Uncompleted confirm future uses the bounded test timeout.
        assertNoAcknowledgement();
        assertEquals(1, stops.queued());
        collector.index(message("invalid"), channel);
        assertNoAcknowledgement();
        assertEquals(2, stops.queued());
    }

    @Test
    void nonMandatoryPublicationIsRefusedBeforeSending() throws Exception {
        when(rabbit.isMandatoryFor(any(Message.class))).thenReturn(false);
        collector.parse(message("invalid"), channel);
        assertNoAcknowledgement();
        verify(rabbit, never()).send(
            anyString(),
            anyString(),
            any(Message.class),
            any(CorrelationData.class)
        );
        assertEquals(1, stops.queued());
    }

    @Test
    void databaseFailurePausesWithoutRejectAndHealthCannotRestartBeforeStopCompletion()
        throws Exception {
        when(
            recovery.acceptDeadLetter(
                anyString(),
                any(JsonNode.class),
                anyInt(),
                nullable(String.class)
            )
        ).thenThrow(new IllegalStateException("sensitive body must not be logged"));
        var completion = new AtomicReference<Runnable>();
        doAnswer(invocation -> {
            completion.set(invocation.getArgument(0));
            return null;
        })
            .when(parseContainer)
            .stop(any(Runnable.class));
        collector.parse(message(event("DOCUMENT_PARSE")), channel);
        assertNoAcknowledgement();
        collector.restartHealthy();
        verifyNoInteractions(database, parseContainer);
        assertEquals(1, stops.queued());
        stops.runNext();
        collector.restartHealthy();
        collector.parse(message(event("DOCUMENT_PARSE")), channel);
        verify(recovery, times(1)).acceptDeadLetter(
            anyString(),
            any(JsonNode.class),
            anyInt(),
            nullable(String.class)
        );
        verifyNoInteractions(database);
        verify(parseContainer, never()).start();
        assertEquals(
            0,
            stops.queued(),
            "A pending stop callback must not issue repeated stop requests"
        );
        completion.get().run();
        doThrow(new IllegalStateException("offline"))
            .when(database)
            .queryForObject("SELECT 1", Integer.class);
        collector.restartHealthy();
        verify(parseContainer, never()).start();
        doReturn(1).when(database).queryForObject("SELECT 1", Integer.class);
        collector.restartHealthy();
        var order = inOrder(database, parseContainer);
        order.verify(database, times(2)).queryForObject("SELECT 1", Integer.class);
        order.verify(parseContainer).start();
        verify(indexContainer, never()).start();
        collector.restartHealthy();
        verify(database, times(2)).queryForObject("SELECT 1", Integer.class);
    }

    @Test
    void ackFailureAfterCommittedResultPausesAndDoesNotReject() throws Exception {
        when(
            recovery.acceptDeadLetter(
                anyString(),
                any(JsonNode.class),
                anyInt(),
                nullable(String.class)
            )
        ).thenReturn(true);
        doThrow(new IOException("closed channel")).when(channel).basicAck(17, false);
        collector.parse(message(event("DOCUMENT_PARSE")), channel);
        assertEquals(1, stops.queued());
        verify(channel, never()).basicNack(anyLong(), anyBoolean(), anyBoolean());
        verify(channel, never()).basicReject(anyLong(), anyBoolean());
    }

    @Test
    void failedStartKeepsOwnedPauseUntilANewCompletedStopAndHealthCheck() {
        when(
            recovery.acceptDeadLetter(
                anyString(),
                any(JsonNode.class),
                anyInt(),
                nullable(String.class)
            )
        ).thenThrow(new IllegalStateException("offline"));
        doAnswer(invocation -> {
            ((Runnable) invocation.getArgument(0)).run();
            return null;
        })
            .when(parseContainer)
            .stop(any(Runnable.class));
        doThrow(new IllegalStateException("connection unavailable"))
            .doNothing()
            .when(parseContainer)
            .start();
        when(database.queryForObject("SELECT 1", Integer.class)).thenReturn(1);
        collector.parse(message(event("DOCUMENT_PARSE")), channel);
        stops.runNext();
        collector.restartHealthy();
        assertEquals(1, stops.queued());
        collector.restartHealthy();
        verify(parseContainer, times(1)).start();
        stops.runNext();
        collector.restartHealthy();
        verify(parseContainer, times(2)).start();
        verify(parseContainer, times(2)).stop(any(Runnable.class));
    }

    @Test
    void closeStopsSchedulingAndDoesNotRestartOwnedConsumers() {
        when(
            recovery.acceptDeadLetter(
                anyString(),
                any(JsonNode.class),
                anyInt(),
                nullable(String.class)
            )
        ).thenThrow(new IllegalStateException("offline"));
        collector.parse(message(event("DOCUMENT_PARSE")), channel);
        collector.close();
        collector.restartHealthy();
        assertTrue(stops.isShutdown());
        verifyNoInteractions(database, parseContainer, indexContainer);
    }

    @Test
    void factoryUsesManualSinglePrefetchAndOnlyOwnedListeners() throws Exception {
        var factory = new TaskDeadLetterCollector.OwnedContainers().taskDeadLetterContainerFactory(
            mock(ConnectionFactory.class)
        );
        var endpoint = new SimpleRabbitListenerEndpoint();
        endpoint.setId("isolated-factory-test");
        endpoint.setQueueNames("isolated-factory-test-queue");
        endpoint.setMessageListener(value -> {});
        var container = factory.createListenerContainer(endpoint);
        assertEquals(AcknowledgeMode.MANUAL, container.getAcknowledgeMode());
        assertEquals(
            1,
            org.springframework.test.util.ReflectionTestUtils.getField(container, "prefetchCount")
        );
        assertEquals(
            1,
            org.springframework.test.util.ReflectionTestUtils.getField(
                container,
                "concurrentConsumers"
            )
        );
        assertEquals(
            true,
            org.springframework.test.util.ReflectionTestUtils.getField(container, "forceStop")
        );
        var parse = TaskDeadLetterCollector.class
            .getMethod("parse", Message.class, Channel.class)
            .getAnnotation(RabbitListener.class);
        var index = TaskDeadLetterCollector.class
            .getMethod("index", Message.class, Channel.class)
            .getAnnotation(RabbitListener.class);
        assertNotEquals(parse.id(), index.id());
        assertEquals("MANUAL", parse.ackMode());
        assertEquals(TaskDeadLetterCollector.CONTAINER_FACTORY, parse.containerFactory());
        assertEquals("${lexflow.delivery.consumer-enabled:true}", parse.autoStartup());
        assertArrayEquals(new String[] { RabbitMqConfig.DLQ_DOCUMENT_PARSE }, parse.queues());
        assertArrayEquals(new String[] { RabbitMqConfig.DLQ_KNOWLEDGE_INGEST }, index.queues());
        assertTrue(new RabbitMqConfig().taskDeliveryQuarantine().isDurable());
        assertEquals(
            RabbitMqConfig.QUEUE_TASK_DELIVERY_QUARANTINE,
            new RabbitMqConfig().taskDeliveryQuarantine().getName()
        );
    }

    private static final class QueuedExecutor extends AbstractExecutorService {

        private final Queue<Runnable> tasks = new ArrayDeque<>();
        private boolean shutdown;

        int queued() {
            return tasks.size();
        }

        void runNext() {
            assertNotNull(tasks.peek(), "Expected an owned asynchronous stop");
            tasks.remove().run();
        }

        @Override
        public void shutdown() {
            shutdown = true;
        }

        @Override
        public List<Runnable> shutdownNow() {
            shutdown = true;
            var pending = new ArrayList<>(tasks);
            tasks.clear();
            return pending;
        }

        @Override
        public boolean isShutdown() {
            return shutdown;
        }

        @Override
        public boolean isTerminated() {
            return shutdown && tasks.isEmpty();
        }

        @Override
        public boolean awaitTermination(long timeout, TimeUnit unit) {
            return isTerminated();
        }

        @Override
        public void execute(Runnable command) {
            if (shutdown) throw new RejectedExecutionException();
            tasks.add(command);
        }
    }
}
