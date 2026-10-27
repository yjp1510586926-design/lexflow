package com.lexflow.website.biz;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.lexflow.dao.AuditLogMapper;
import com.lexflow.framework.exception.BusinessException;
import com.lexflow.service.AiConfigurationService;
import com.lexflow.service.IdentityService;
import com.lexflow.service.identity.Identity;
import org.junit.jupiter.api.Test;

class AiConfigurationBizTest {

    @Test
    void nonAdminCannotReadSaveOrDelete() {
        var configs = mock(AiConfigurationService.class);
        var identities = mock(IdentityService.class);
        var audit = mock(AuditLogMapper.class);
        var user = new Identity(1, 7, 0, "test");
        when(identities.active(user)).thenReturn(true);
        var biz = new AiConfigurationBiz(configs, identities, audit);
        assertThrows(BusinessException.class, () -> biz.list(user));
        assertThrows(BusinessException.class, () ->
            biz.save(user, "LLM", "custom", "https://example.com", "model", "secret")
        );
        assertThrows(BusinessException.class, () -> biz.delete(user, "LLM"));
        verifyNoInteractions(configs, audit);
    }
}
