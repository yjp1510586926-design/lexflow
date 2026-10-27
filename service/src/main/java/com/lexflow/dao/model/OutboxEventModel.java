package com.lexflow.dao.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.lexflow.framework.model.BaseModel;
import java.time.LocalDateTime;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

@Data
@SuperBuilder
@NoArgsConstructor
@EqualsAndHashCode(callSuper = true)
@TableName("outbox_event")
public class OutboxEventModel extends BaseModel {

    @TableField(exist = false)
    private Long id;

    @TableId(value = "event_id", type = IdType.INPUT)
    private String eventId;

    @TableField("tenant_id")
    private Long tenantId;

    @TableField("task_id")
    private Long taskId;

    @TableField("routing_key")
    private String routingKey;

    @TableField("payload")
    private String payload;

    @TableField("status")
    private String status;

    @TableField("attempt")
    private Integer attempt;

    @TableField("next_retry_at")
    private LocalDateTime nextRetryAt;

    @TableField("sent_at")
    private LocalDateTime sentAt;
}
