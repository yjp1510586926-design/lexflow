package com.lexflow.website.core.config;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.ExchangeBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * RabbitMQ 拓扑声明：
 * - 主 Exchange：lexflow.tasks（topic）
 * - 死信 Exchange：lexflow.dlx（topic）
 * - 主要队列 + 对应死信队列
 *
 * Routing key 约定：<type>.<action>.v1
 * 例：document.parse.requested.v1
 */
@Configuration
public class RabbitMqConfig {

    // ── Exchange 名 ───────────────────────────────────────────────────────────

    public static final String TASKS_EXCHANGE = "lexflow.tasks";
    public static final String DLX_EXCHANGE = "lexflow.dlx";
    public static final String RESULTS_EXCHANGE = "lexflow.results";

    // ── Queue 名 ─────────────────────────────────────────────────────────────

    public static final String QUEUE_DOCUMENT_PARSE = "lexflow.q.document.parse";
    public static final String QUEUE_CONTRACT_REVIEW = "lexflow.q.contract.review";
    public static final String QUEUE_CASE_ANALYSIS = "lexflow.q.case.analysis";
    public static final String DLQ_CASE_ANALYSIS = QUEUE_CASE_ANALYSIS + ".dlq";
    public static final String QUEUE_KNOWLEDGE_INGEST = "lexflow.q.knowledge.ingest";
    public static final String QUEUE_DOCUMENT_EXPORT = "lexflow.q.document.export";
    public static final String QUEUE_DOCUMENT_GENERATE = "lexflow.q.document.generate";
    public static final String DLQ_DOCUMENT_GENERATE = QUEUE_DOCUMENT_GENERATE + ".dlq";
    public static final String DLQ_DOCUMENT_EXPORT = QUEUE_DOCUMENT_EXPORT + ".dlq";
    public static final String RK_DOCUMENT_EXPORT = "document.export.requested.v1";
    public static final String QUEUE_AI_RESULTS = "lexflow.q.ai.results";

    public static final String DLQ_DOCUMENT_PARSE = QUEUE_DOCUMENT_PARSE + ".dlq";
    public static final String DLQ_CONTRACT_REVIEW = QUEUE_CONTRACT_REVIEW + ".dlq";
    public static final String DLQ_KNOWLEDGE_INGEST = QUEUE_KNOWLEDGE_INGEST + ".dlq";
    public static final String QUEUE_TASK_DELIVERY_QUARANTINE =
        "lexflow.q.task.delivery.quarantine";

    // ── Routing Key 模式 ──────────────────────────────────────────────────────

    public static final String RK_DOCUMENT_PARSE = "document.parse.requested.v1";
    public static final String RK_CONTRACT_REVIEW = "contract.review.requested.v1";
    public static final String RK_KNOWLEDGE_INGEST = "knowledge.ingest.requested.v1";
    public static final String RK_AI_COMPLETED = "ai.task.completed.v1";
    public static final String RK_AI_FAILED = "ai.task.failed.v1";

    @Value("${spring.rabbitmq.host:127.0.0.1}")
    private String host;

    // ── Exchange 声明 ─────────────────────────────────────────────────────────

    @Bean
    public TopicExchange tasksExchange() {
        return ExchangeBuilder.topicExchange(TASKS_EXCHANGE).durable(true).build();
    }

    @Bean
    public TopicExchange dlxExchange() {
        return ExchangeBuilder.topicExchange(DLX_EXCHANGE).durable(true).build();
    }

    @Bean
    public TopicExchange resultsExchange() {
        return ExchangeBuilder.topicExchange(RESULTS_EXCHANGE).durable(true).build();
    }

    // ── 工作队列（附 x-dead-letter 参数）────────────────────────────────────

    private Queue workQueue(String name, String dlqName) {
        return QueueBuilder.durable(name)
            .withArgument("x-dead-letter-exchange", DLX_EXCHANGE)
            .withArgument("x-dead-letter-routing-key", dlqName)
            .build();
    }

    @Bean
    public Queue qDocumentParse() {
        return workQueue(QUEUE_DOCUMENT_PARSE, DLQ_DOCUMENT_PARSE);
    }

    @Bean
    public Queue qContractReview() {
        return workQueue(QUEUE_CONTRACT_REVIEW, DLQ_CONTRACT_REVIEW);
    }

    @Bean
    public Queue qCaseAnalysis() {
        return workQueue(QUEUE_CASE_ANALYSIS, DLQ_CASE_ANALYSIS);
    }

    @Bean
    public Queue qKnowledgeIngest() {
        return workQueue(QUEUE_KNOWLEDGE_INGEST, DLQ_KNOWLEDGE_INGEST);
    }

    @Bean
    public Queue qDocumentExport() {
        return workQueue(QUEUE_DOCUMENT_EXPORT, DLQ_DOCUMENT_EXPORT);
    }

    @Bean
    public Queue qDocumentGenerate() {
        return workQueue(QUEUE_DOCUMENT_GENERATE, DLQ_DOCUMENT_GENERATE);
    }

    private Queue retryQueue(String queue, int retry, int ttl) {
        // 延迟队列到期后经默认 exchange 回到原工作队列，不进入最终人工处理的 DLQ。
        return QueueBuilder.durable(queue + ".retry." + retry)
            .withArgument("x-message-ttl", ttl)
            .withArgument("x-dead-letter-exchange", "")
            .withArgument("x-dead-letter-routing-key", queue)
            .build();
    }

    @Bean
    public Queue parseRetry1() {
        return retryQueue(QUEUE_DOCUMENT_PARSE, 1, 10000);
    }

    @Bean
    public Queue parseRetry2() {
        return retryQueue(QUEUE_DOCUMENT_PARSE, 2, 60000);
    }

    @Bean
    public Queue parseRetry3() {
        return retryQueue(QUEUE_DOCUMENT_PARSE, 3, 300000);
    }

    @Bean
    public Queue indexRetry1() {
        return retryQueue(QUEUE_KNOWLEDGE_INGEST, 1, 10000);
    }

    @Bean
    public Queue indexRetry2() {
        return retryQueue(QUEUE_KNOWLEDGE_INGEST, 2, 60000);
    }

    @Bean
    public Queue indexRetry3() {
        return retryQueue(QUEUE_KNOWLEDGE_INGEST, 3, 300000);
    }

    @Bean
    public Queue contractRetry1() {
        return retryQueue(QUEUE_CONTRACT_REVIEW, 1, 10000);
    }

    @Bean
    public Queue contractRetry2() {
        return retryQueue(QUEUE_CONTRACT_REVIEW, 2, 60000);
    }

    @Bean
    public Queue contractRetry3() {
        return retryQueue(QUEUE_CONTRACT_REVIEW, 3, 300000);
    }

    @Bean
    public Queue generationRetry1() {
        return retryQueue(QUEUE_DOCUMENT_GENERATE, 1, 10000);
    }

    @Bean
    public Queue generationRetry2() {
        return retryQueue(QUEUE_DOCUMENT_GENERATE, 2, 60000);
    }

    @Bean
    public Queue generationRetry3() {
        return retryQueue(QUEUE_DOCUMENT_GENERATE, 3, 300000);
    }

    @Bean
    public Queue analysisRetry1() {
        return retryQueue(QUEUE_CASE_ANALYSIS, 1, 10000);
    }

    @Bean
    public Queue analysisRetry2() {
        return retryQueue(QUEUE_CASE_ANALYSIS, 2, 60000);
    }

    @Bean
    public Queue analysisRetry3() {
        return retryQueue(QUEUE_CASE_ANALYSIS, 3, 300000);
    }

    @Bean
    public Queue qAiResults() {
        return QueueBuilder.durable(QUEUE_AI_RESULTS).build();
    }

    // ── 死信队列（DLQ） ────────────────────────────────────────────────────

    @Bean
    public Queue dlqDocumentExport() {
        return QueueBuilder.durable(DLQ_DOCUMENT_EXPORT).build();
    }

    @Bean
    public Queue dlqDocumentGenerate() {
        return QueueBuilder.durable(DLQ_DOCUMENT_GENERATE).build();
    }

    @Bean
    public Queue dlqDocumentParse() {
        return QueueBuilder.durable(DLQ_DOCUMENT_PARSE).build();
    }

    @Bean
    public Queue dlqContractReview() {
        return QueueBuilder.durable(DLQ_CONTRACT_REVIEW).build();
    }

    @Bean
    public Queue dlqCaseAnalysis() {
        return QueueBuilder.durable(DLQ_CASE_ANALYSIS).build();
    }

    @Bean
    public Queue dlqKnowledgeIngest() {
        return QueueBuilder.durable(DLQ_KNOWLEDGE_INGEST).build();
    }

    @Bean
    public Queue taskDeliveryQuarantine() {
        return QueueBuilder.durable(QUEUE_TASK_DELIVERY_QUARANTINE).build();
    }

    // ── Binding ───────────────────────────────────────────────────────────────

    @Bean
    public Binding bindDocumentParse() {
        return BindingBuilder.bind(qDocumentParse()).to(tasksExchange()).with(RK_DOCUMENT_PARSE);
    }

    @Bean
    public Binding bindContractReview() {
        return BindingBuilder.bind(qContractReview()).to(tasksExchange()).with(RK_CONTRACT_REVIEW);
    }

    @Bean
    public Binding bindCaseAnalysis() {
        return BindingBuilder.bind(qCaseAnalysis())
            .to(tasksExchange())
            .with("case.analysis.requested.v1");
    }

    @Bean
    public Binding bindKnowledgeIngest() {
        return BindingBuilder.bind(qKnowledgeIngest())
            .to(tasksExchange())
            .with(RK_KNOWLEDGE_INGEST);
    }

    @Bean
    public Binding bindDocumentExport() {
        return BindingBuilder.bind(qDocumentExport()).to(tasksExchange()).with(RK_DOCUMENT_EXPORT);
    }

    @Bean
    public Binding bindDocumentGenerate() {
        return BindingBuilder.bind(qDocumentGenerate())
            .to(tasksExchange())
            .with("document.generate.requested.v1");
    }

    @Bean
    public Binding bindAiResults() {
        return BindingBuilder.bind(qAiResults()).to(resultsExchange()).with("ai.task.#");
    }

    @Bean
    public Binding bindDlqDocumentExport() {
        return BindingBuilder.bind(dlqDocumentExport()).to(dlxExchange()).with(DLQ_DOCUMENT_EXPORT);
    }

    @Bean
    public Binding bindDlqDocumentGenerate() {
        return BindingBuilder.bind(dlqDocumentGenerate())
            .to(dlxExchange())
            .with(DLQ_DOCUMENT_GENERATE);
    }

    @Bean
    public Binding bindDlqDocumentParse() {
        return BindingBuilder.bind(dlqDocumentParse()).to(dlxExchange()).with(DLQ_DOCUMENT_PARSE);
    }

    @Bean
    public Binding bindDlqContractReview() {
        return BindingBuilder.bind(dlqContractReview()).to(dlxExchange()).with(DLQ_CONTRACT_REVIEW);
    }

    @Bean
    public Binding bindDlqCaseAnalysis() {
        return BindingBuilder.bind(dlqCaseAnalysis()).to(dlxExchange()).with(DLQ_CASE_ANALYSIS);
    }

    @Bean
    public Binding bindDlqKnowledgeIngest() {
        return BindingBuilder.bind(dlqKnowledgeIngest())
            .to(dlxExchange())
            .with(DLQ_KNOWLEDGE_INGEST);
    }

    // ── RabbitTemplate（publisher confirm + JSON 序列化）────────────────────

    @Bean
    public Jackson2JsonMessageConverter messageConverter() {
        return new Jackson2JsonMessageConverter();
    }

    @Bean
    public RabbitTemplate rabbitTemplate(
        ConnectionFactory cf,
        Jackson2JsonMessageConverter converter
    ) {
        var tpl = new RabbitTemplate(cf);
        tpl.setMessageConverter(converter);
        tpl.setMandatory(true);
        // 发布方等待逐条 publisher confirm，并检查 mandatory 退回，确认消息已实际路由。
        return tpl;
    }
}
