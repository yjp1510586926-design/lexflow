package com.lexflow.dao;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lexflow.dao.model.DocumentVersionModel;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface DocumentVersionMapper extends BaseMapper<DocumentVersionModel> {
    // 权限查询可能在取得案件锁前建立旧的 REPEATABLE READ 快照。
    // 版本号须从锁定当前读分配，包含并发保存后已提交的 AI 草稿版本。
    @Select(
        "SELECT COALESCE(MAX(version_no),0)+1 FROM document_version WHERE tenant_id=#{tid} AND document_id=#{docId} FOR UPDATE"
    )
    int nextVersionNo(@Param("tid") long tenantId, @Param("docId") long docId);

    @Override
    int updateById(@Param("et") DocumentVersionModel entity);
}
