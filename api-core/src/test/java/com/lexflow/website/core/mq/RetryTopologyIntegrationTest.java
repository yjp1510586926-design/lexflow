package com.lexflow.website.core.mq;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lexflow.website.core.config.RabbitMqConfig;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

@EnabledIfEnvironmentVariable(named = "LEXFLOW_LOCAL_INTEGRATION", matches = "true")
class RetryTopologyIntegrationTest {

    @Test
    void isolatedBrokerDeclaresAllRetriesAndReturnsOriginalBytesAfterTtl() throws Exception {
        String vhost = "lexflow-java-retry-test-" + UUID.randomUUID();
        String base = "http://127.0.0.1:15673/api/vhosts/" + vhost;
        HttpClient http = HttpClient.newBuilder()
            .connectTimeout(java.time.Duration.ofSeconds(5))
            .build();
        String auth =
            "Basic " +
            Base64.getEncoder().encodeToString(
                "lexflow:lexflow_mq_dev_only".getBytes(StandardCharsets.UTF_8)
            );
        assertEquals(404, call(http, auth, base, "GET", null).statusCode());
        boolean created = false;
        var connection = new CachingConnectionFactory("127.0.0.1", 15672);
        connection.setUsername("lexflow");
        connection.setPassword("lexflow_mq_dev_only");
        connection.setVirtualHost(vhost);
        try {
            assertEquals(201, call(http, auth, base, "PUT", "{}").statusCode());
            created = true;
            assertTrue(
                Set.of(201, 204).contains(
                    call(
                        http,
                        auth,
                        "http://127.0.0.1:15673/api/permissions/" + vhost + "/lexflow",
                        "PUT",
                        "{\"configure\":\".*\",\"write\":\".*\",\"read\":\".*\"}"
                    ).statusCode()
                )
            );
            var config = new RabbitMqConfig();
            var admin = new RabbitAdmin(connection);
            admin.declareExchange(config.dlxExchange());
            admin.declareQueue(config.qDocumentParse());
            admin.declareQueue(config.qKnowledgeIngest());
            for (var queue : List.of(
                config.parseRetry1(),
                config.parseRetry2(),
                config.parseRetry3(),
                config.indexRetry1(),
                config.indexRetry2(),
                config.indexRetry3()
            )) {
                admin.declareQueue(queue);
                var response = call(
                    http,
                    auth,
                    "http://127.0.0.1:15673/api/queues/" + vhost + "/" + queue.getName(),
                    "GET",
                    null
                );
                assertEquals(200, response.statusCode());
                var args = new ObjectMapper().readTree(response.body()).get("arguments");
                assertEquals(
                    ((Number) queue.getArguments().get("x-message-ttl")).intValue(),
                    args.get("x-message-ttl").intValue()
                );
                assertEquals("", args.get("x-dead-letter-exchange").textValue());
                assertEquals(
                    queue.getArguments().get("x-dead-letter-routing-key"),
                    args.get("x-dead-letter-routing-key").textValue()
                );
            }
            var rabbit = new RabbitTemplate(connection);
            byte[] bytes = "仅供测试的原始 UTF-8 事件".getBytes(StandardCharsets.UTF_8);
            var properties = new MessageProperties();
            properties.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
            properties.setHeader("x-lexflow-delivery-retry", 1);
            rabbit.send(
                "",
                RabbitMqConfig.QUEUE_DOCUMENT_PARSE + ".retry.1",
                new Message(bytes, properties)
            );
            Message returned = rabbit.receive(RabbitMqConfig.QUEUE_DOCUMENT_PARSE, 20000);
            assertNotNull(returned);
            assertArrayEquals(bytes, returned.getBody());
            assertEquals(
                1,
                (
                    (Number) returned.getMessageProperties().getHeader("x-lexflow-delivery-retry")
                ).intValue()
            );
        } finally {
            connection.destroy();
            if (created) {
                assertEquals(204, call(http, auth, base, "DELETE", null).statusCode());
                assertEquals(404, call(http, auth, base, "GET", null).statusCode());
            }
        }
    }

    private HttpResponse<String> call(
        HttpClient http,
        String auth,
        String url,
        String method,
        String body
    ) throws Exception {
        var request = HttpRequest.newBuilder(URI.create(url))
            .timeout(java.time.Duration.ofSeconds(10))
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
}
