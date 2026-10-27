package com.lexflow.service;

import com.lexflow.dao.SysUserMapper;
import com.lexflow.dao.model.SysUserModel;
import com.lexflow.service.identity.Identity;
import java.util.Map;
import java.util.Optional;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

/**
 * 认证与授权查询服务；用户、租户成员关系和租户状态均以数据库当前值为准。
 */
@Service
public class IdentityService extends BaseService<SysUserModel, SysUserMapper> {

    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(12);
    private final String dummyHash = encoder.encode("invalid-account-dummy");

    public IdentityService(SysUserMapper mapper) {
        super(mapper);
    }

    public Optional<Identity> authenticate(String username, String password, long tenantId) {
        Map<String, Object> userRow = mapper.findAuthUser(username, tenantId);
        // 账号不存在时仍执行 BCrypt 校验，缩小不同账号状态的响应耗时差异。
        String passwordHash =
            userRow == null || userRow.isEmpty()
                ? dummyHash
                : (String) userRow.get("password_hash");
        boolean matches = encoder.matches(password, passwordHash);
        if (!matches || userRow == null || userRow.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(
            new Identity(
                ((Number) userRow.get("id")).longValue(),
                tenantId,
                ((Number) userRow.get("session_version")).longValue(),
                (String) userRow.get("display_name")
            )
        );
    }

    public boolean active(Identity identity) {
        if (identity == null) {
            return false;
        }
        // 会话版本必须仍匹配，且用户、成员关系和租户都有效；不能只凭令牌中的旧身份。
        return (
            mapper.countActiveSession(
                identity.userId(),
                identity.tenantId(),
                identity.sessionVersion()
            ) == 1
        );
    }

    public boolean hasPermission(Identity identity, String code) {
        if (identity == null) {
            return false;
        }
        return mapper.countPermission(identity.tenantId(), identity.userId(), code) > 0;
    }

    public boolean admin(Identity identity) {
        if (identity == null) {
            return false;
        }
        return mapper.countAdminRole(identity.tenantId(), identity.userId()) > 0;
    }
}
