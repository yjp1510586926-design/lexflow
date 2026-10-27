package com.lexflow.dao.model;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
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
@TableName("case_member")
public class CaseMemberModel extends BaseModel {

    @TableField(exist = false)
    private Long id;

    @TableField("tenant_id")
    private Long tenantId;

    @TableField("case_id")
    private Long caseId;

    @TableField("user_id")
    private Long userId;

    @TableField("member_role")
    private String memberRole;

    @TableField(value = "joined_at", insertStrategy = FieldStrategy.NEVER)
    private LocalDateTime joinedAt;
}
