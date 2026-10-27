package com.lexflow.dao.model;

import com.baomidou.mybatisplus.annotation.TableField;
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
@TableName("file_object")
public class FileObjectModel extends BaseModel {

    @TableField("tenant_id")
    private Long tenantId;

    @TableField("case_id")
    private Long caseId;

    @TableField("scope")
    private String scope;

    @TableField("knowledge_base_id")
    private Long knowledgeBaseId;

    @TableField("display_name")
    private String displayName;

    @TableField("status")
    private String status;

    @TableField("created_by")
    private Long createdBy;

    @TableField("deleted_at")
    private LocalDateTime deletedAt;
}
