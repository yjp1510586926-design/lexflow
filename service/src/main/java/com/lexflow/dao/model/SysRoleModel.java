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
@TableName("sys_role")
public class SysRoleModel extends BaseModel {

    @TableField("tenant_id")
    private Long tenantId;

    @TableField("code")
    private String code;

    @TableField("name")
    private String name;
}
