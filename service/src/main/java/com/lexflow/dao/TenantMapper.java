package com.lexflow.dao;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lexflow.dao.model.TenantModel;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface TenantMapper extends BaseMapper<TenantModel> {
    @Select("SELECT id FROM tenant WHERE id = #{id} FOR UPDATE")
    Long lockTenant(@Param("id") Long id);

    @Select("SELECT initialized FROM installation_guard WHERE id = 1 FOR UPDATE")
    Boolean checkInstallationGuard();

    @Update("UPDATE installation_guard SET initialized = TRUE WHERE id = 1")
    int markInstalled();
}
