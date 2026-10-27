package com.lexflow.framework.model;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import java.time.LocalDateTime;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

/**
 * 持久化实体共用的主键与审计字段；主键使用数据库自增。
 * createdAt 由数据库维护，ORM 插入和更新均不写入该列。
 * createdBy、updatedBy、updatedAt 默认不映射，具体子表须按实际 DDL 声明启用。
 */
@Data
@SuperBuilder
@NoArgsConstructor
public class BaseModel {

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField(
        value = "created_at",
        insertStrategy = FieldStrategy.NEVER,
        updateStrategy = FieldStrategy.NEVER
    )
    private LocalDateTime createdAt;

    @TableField(exist = false)
    private Long createdBy;

    @TableField(exist = false)
    private Long updatedBy;

    @TableField(exist = false)
    private LocalDateTime updatedAt;
}
