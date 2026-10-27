package com.lexflow.dao.model;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import com.lexflow.framework.model.BaseModel;
import java.time.Instant;
import java.time.LocalDate;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

@Data
@SuperBuilder
@NoArgsConstructor
@EqualsAndHashCode(callSuper = true)
@TableName("case_info")
public class CaseModel extends BaseModel {

    @TableField("tenant_id")
    private Long tenantId;

    @TableField("case_no")
    private String caseNo;

    @TableField("title")
    private String title;

    @TableField("case_type")
    private String caseType;

    @TableField("status")
    private String status;

    @TableField("description")
    private String description;

    @TableField("owner_id")
    private Long ownerId;

    @TableField("filed_on")
    private LocalDate filedOn;

    @TableField("archived_at")
    private Instant archivedAt;

    @Version
    @TableField("version")
    private Long version;
}
