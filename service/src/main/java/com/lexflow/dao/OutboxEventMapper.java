package com.lexflow.dao;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lexflow.dao.model.OutboxEventModel;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** 本地事务只登记待投递事件；发布器负责发送和退避，消费者仍须处理重复投递。 */
@Mapper
public interface OutboxEventMapper extends BaseMapper<OutboxEventModel> {
    /** 查询不抢占事件，多实例或确认后故障均可能重复发送；消费者须按任务执行身份去重。 */
    @Select(
        """
            SELECT event_id, tenant_id, task_id, routing_key, payload, status, attempt, next_retry_at, sent_at
            FROM outbox_event
            WHERE status = 'PENDING' AND next_retry_at <= NOW(6)
            ORDER BY next_retry_at
            LIMIT #{batchSize}
        """
    )
    List<OutboxEventModel> selectPendingEvents(@Param("batchSize") int batchSize);

    @Update(
        "UPDATE outbox_event SET status = 'SENT', attempt = #{attempt}, sent_at = NOW() WHERE event_id = #{eventId}"
    )
    int markSent(@Param("eventId") String eventId, @Param("attempt") int attempt);

    @Update(
        "UPDATE outbox_event SET status = 'FAILED', attempt = #{attempt} WHERE event_id = #{eventId}"
    )
    int markFailed(@Param("eventId") String eventId, @Param("attempt") int attempt);

    @Update(
        "UPDATE outbox_event SET attempt = #{attempt}, next_retry_at = DATE_ADD(NOW(), INTERVAL #{backoffSeconds} SECOND) WHERE event_id = #{eventId}"
    )
    int scheduleRetry(
        @Param("eventId") String eventId,
        @Param("attempt") int attempt,
        @Param("backoffSeconds") long backoffSeconds
    );
}
