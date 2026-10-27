package com.lexflow.website.core.storage;

import com.lexflow.service.KnowledgeVectorArtifactService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 先通过服务事务提交向量退役与回收租约，再在事务外调用内部删除接口。 */
@Component
@ConditionalOnProperty(name = "scheduling.enabled", havingValue = "true", matchIfMissing = true)
public class KnowledgeVectorRetirementWorker {

    private final KnowledgeVectorArtifactService artifacts;
    private final KnowledgeVectorRetirementClient client;

    public KnowledgeVectorRetirementWorker(
        KnowledgeVectorArtifactService artifacts,
        KnowledgeVectorRetirementClient client
    ) {
        this.artifacts = artifacts;
        this.client = client;
    }

    @Scheduled(fixedDelay = 10000, initialDelay = 20000)
    public void clean() {
        for (long id : artifacts.candidates()) {
            var reservation = artifacts.reserve(id);
            // reserve 会保护仍发布或执行中的向量；完成回写只接受本次租约，迟到响应不会释放新租约。
            if (reservation != null) {
                artifacts.finish(reservation, client.delete(reservation));
            }
        }
    }
}
