package com.lexflow.dao.model;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
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
@TableName("async_task")
public class AsyncTaskModel extends BaseModel {

    @TableField("tenant_id")
    private Long tenantId;

    @TableField("case_id")
    private Long caseId;

    @TableField("type")
    private String type;

    @TableField("resource_version_id")
    private Long resourceVersionId;

    @TableField("status")
    private String status;

    @TableField("idempotency_key")
    private String idempotencyKey;

    @TableField("attempt")
    private Integer attempt;

    /** 防止迟到消息覆盖终态 */
    @Version
    @TableField("state_version")
    private Integer stateVersion;

    @TableField("error_message")
    private String errorMessage;

    @TableField("result_object_key")
    private String resultObjectKey;

    @TableField("created_by")
    private Long createdBy;

    @TableField("started_at")
    private LocalDateTime startedAt;

    @TableField("finished_at")
    private LocalDateTime finishedAt;

    @TableField(value = "updated_at", insertStrategy = FieldStrategy.NEVER)
    private LocalDateTime updatedAt;
}
