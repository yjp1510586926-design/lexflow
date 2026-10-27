package com.lexflow.dao;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lexflow.dao.model.FileVersionModel;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface FileVersionMapper extends BaseMapper<FileVersionModel> {
    @Select(
        "SELECT COALESCE(MAX(version_no),0)+1 FROM file_version WHERE tenant_id=#{tid} AND file_id=#{fileId}"
    )
    int nextVersionNo(@Param("tid") long tenantId, @Param("fileId") long fileId);
}
