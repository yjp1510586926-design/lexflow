package com.lexflow.website;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(
    properties = {
        "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration,org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration,org.springframework.boot.autoconfigure.session.SessionAutoConfiguration",
    }
)
@AutoConfigureMockMvc
class SystemStatusTest {

    @org.springframework.test.context.bean.override.mockito.MockitoBean
    com.lexflow.service.IdentityService identities;

    @org.springframework.test.context.bean.override.mockito.MockitoBean
    com.lexflow.service.CaseService cases;

    @org.springframework.test.context.bean.override.mockito.MockitoBean
    com.lexflow.service.OrganizationService organizations;

    @Autowired
    MockMvc mvc;

    @Test
    void healthEndpointWiresAllLayers() throws Exception {
        mvc.perform(get("/api/health"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(200))
            .andExpect(jsonPath("$.data.application").value("lexflow-core"))
            .andExpect(jsonPath("$.data.status").value("UP"));
    }
}
