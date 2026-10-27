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
@TableName("file_version")
public class FileVersionModel extends BaseModel {

    @TableField("tenant_id")
    private Long tenantId;

    @TableField("file_id")
    private Long fileId;

    @TableField("version_no")
    private Integer versionNo;

    @TableField("object_key")
    private String objectKey;

    @TableField("sha256")
    private String sha256;

    @TableField("size_bytes")
    private Long sizeBytes;

    @TableField("mime_type")
    private String mimeType;
}
