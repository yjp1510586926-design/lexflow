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
@TableName("document")
public class DocumentModel extends BaseModel {

    @TableField("tenant_id")
    private Long tenantId;

    @TableField("case_id")
    private Long caseId;

    @TableField("title")
    private String title;

    @TableField("current_version_id")
    private Long currentVersionId;

    @TableField("status")
    private String status;

    @TableField("created_by")
    private Long createdBy;

    @TableField(value = "updated_at", insertStrategy = FieldStrategy.NEVER)
    private LocalDateTime updatedAt;

    @Version
    @TableField("version")
    private Long version;
}
