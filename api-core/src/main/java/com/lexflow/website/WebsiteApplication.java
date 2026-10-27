package com.lexflow.website;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * LexFlow 律所业务协作平台启动类。
 * <p>
 * {@code scanBasePackages = "com.lexflow"} 覆盖 service/dao/framework 等跨模块包，
 * 确保各模块的 Spring 组件均能被扫描到。
 * {@link EnableScheduling} 启用 Outbox 发布器、文件清理、审计回放等后台定时任务；
 * {@link EnableAsync} 允许后台任务在独立线程池中异步执行，避免阻塞业务请求。
 */
@SpringBootApplication(scanBasePackages = "com.lexflow")
@EnableScheduling
@EnableAsync
public class WebsiteApplication {

    public static void main(String[] args) {
        SpringApplication.run(WebsiteApplication.class, args);
    }
}
