package com.lexflow.dao;

import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface AiConfigurationMapper {
    @Select(
        "SELECT purpose,provider,base_url AS baseUrl,model_name AS model,revision,updated_at AS updatedAt FROM ai_configuration WHERE tenant_id=#{tenant} ORDER BY purpose"
    )
    List<Map<String, Object>> list(@Param("tenant") long tenant);

    @Insert(
        """
        INSERT INTO ai_configuration(tenant_id,purpose,provider,base_url,model_name,encrypted_key,revision)
        VALUES(#{tenant},#{purpose},#{provider},#{url},#{model},#{ciphertext},UUID())
        ON DUPLICATE KEY UPDATE provider=VALUES(provider),base_url=VALUES(base_url),
        model_name=VALUES(model_name),encrypted_key=VALUES(encrypted_key),revision=UUID()
        """
    )
    void save(
        @Param("tenant") long tenant,
        @Param("purpose") String purpose,
        @Param("provider") String provider,
        @Param("url") String url,
        @Param("model") String model,
        @Param("ciphertext") String ciphertext
    );

    @Delete("DELETE FROM ai_configuration WHERE tenant_id=#{tenant} AND purpose=#{purpose}")
    int delete(@Param("tenant") long tenant, @Param("purpose") String purpose);

    @Select(
        "SELECT provider,base_url AS baseUrl,model_name AS model,encrypted_key AS encryptedKey,revision FROM ai_configuration WHERE tenant_id=#{tenant} AND purpose=#{purpose}"
    )
    Map<String, Object> internal(@Param("tenant") long tenant, @Param("purpose") String purpose);

    @Select(
        "SELECT provider,base_url AS baseUrl,model_name AS model,encrypted_key AS encryptedKey,revision FROM ai_configuration WHERE tenant_id=#{tenant} AND purpose=#{purpose} FOR UPDATE"
    )
    Map<String, Object> lockInternal(
        @Param("tenant") long tenant,
        @Param("purpose") String purpose
    );
}
