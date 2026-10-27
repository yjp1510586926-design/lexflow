package com.lexflow.dao.model;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.lexflow.framework.model.BaseModel;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

@Data
@SuperBuilder
@NoArgsConstructor
@EqualsAndHashCode(callSuper = true)
@TableName("audit_log")
public class AuditLogModel extends BaseModel {

    @TableField("tenant_id")
    private Long tenantId;

    @TableField("actor_id")
    private Long actorId;

    @TableField("action")
    private String action;

    @TableField("object_type")
    private String objectType;

    @TableField("object_id")
    private String objectId;

    @TableField("result")
    private String result;

    @TableField("trace_id")
    private String traceId;

    @TableField("detail_json")
    private String detailJson;
}
