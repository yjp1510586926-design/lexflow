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
@TableName("document_version")
public class DocumentVersionModel extends BaseModel {

    @TableField("tenant_id")
    private Long tenantId;

    @TableField("document_id")
    private Long documentId;

    @TableField("version_no")
    private Integer versionNo;

    @TableField("content_text")
    private String contentText;

    @TableField("content_object_key")
    private String contentObjectKey;

    @TableField("source")
    private String source;

    @TableField("review_status")
    private String reviewStatus;

    @TableField("source_task_id")
    private Long sourceTaskId;

    @TableField("created_by")
    private Long createdBy;
}
