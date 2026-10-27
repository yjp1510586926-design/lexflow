package com.lexflow.website.core.bootstrap;

import com.lexflow.service.OrganizationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * 首次部署时的初始化引导：创建默认律所、管理员账号。
 * <p>
 * 仅在 Spring Profile {@code bootstrap} 激活时生效，防止正常启动时误触发。
 * 执行完成后须从启动命令中移除 {@code bootstrap} profile，否则每次启动均会尝试初始化。
 * 所需环境变量：{@code LEXFLOW_TENANT_NAME}、{@code LEXFLOW_ADMIN_USERNAME}、{@code LEXFLOW_ADMIN_PASSWORD}。
 */
@Component
@Profile("bootstrap")
public class InstallationBootstrap implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(InstallationBootstrap.class);
    private final OrganizationService organizations;
    private final Environment environment;

    public InstallationBootstrap(OrganizationService organizations, Environment environment) {
        this.organizations = organizations;
        this.environment = environment;
    }

    @Override
    public void run(ApplicationArguments args) {
        String password = environment.getRequiredProperty("LEXFLOW_ADMIN_PASSWORD");
        String tenant = organizations.initialize(
            environment.getRequiredProperty("LEXFLOW_TENANT_NAME"),
            environment.getRequiredProperty("LEXFLOW_ADMIN_USERNAME"),
            password
        );
        log.info("首次初始化完成，律所 ID={}。以后启动请去掉 bootstrap profile。", tenant);
    }
}
