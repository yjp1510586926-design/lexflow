package com.lexflow.website.core.config;

import com.lexflow.framework.model.ApiResponse;
import com.lexflow.service.IdentityService;
import com.lexflow.service.audit.RequestAuditWriter;
import com.lexflow.service.identity.Identity;
import com.lexflow.website.core.filter.InternalSecurityFilter;
import com.lexflow.website.core.filter.RequestAuditFilter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextHolderFilter;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Spring Security 配置：定义用户会话、CSRF、鉴权规则、登出流程及过滤器链组装。
 * <p>
 * 内部接口（{@code /internal/**}）免用户会话和 CSRF，
 * 但必须通过 {@link com.lexflow.website.core.filter.InternalSecurityFilter} 的共享密钙鉴权；
 * 浏览器写操作使用会话 CSRF Token，登录后必须更新 CSRF 缓存。
 */
@Configuration
public class SecurityConfig {

    /**
     * 禁用 {@link InternalSecurityFilter} 的 Servlet 容器自动注册，
     * 保持其仅在 Spring Security 过滤器链中挂载一次，避免请求被重复过滤。
     */
    @Bean
    FilterRegistrationBean<InternalSecurityFilter> internalRegistration(
        InternalSecurityFilter filter
    ) {
        // 只在 Spring Security 链中挂载一次，避免 @Component 又注册为容器过滤器。
        var registration = new FilterRegistrationBean<>(filter);
        registration.setEnabled(false);
        return registration;
    }

    /**
     * 使用 {@link HttpSessionSecurityContextRepository} 将已认证身份持久化到 HTTP Session，
     * 确保登录后的后续请求能正确加载已验证的 SecurityContext。
     */
    @Bean
    SecurityContextRepository contextRepository() {
        return new HttpSessionSecurityContextRepository();
    }

    /**
     * 构建核心 Security 过滤器链。
     * <p>
     * 链中关键节点说明：
     * <ol>
     *   <li>{@link com.lexflow.website.core.filter.InternalSecurityFilter} 在 CSRF 过滤器之前校验内部密鐐</li>
     *   <li>{@link com.lexflow.website.core.filter.RequestAuditFilter} 在 SecurityContext 加载后记录登录、登出和失败请求</li>
     *   <li>已激活尝试对每次请求校验用户和会话状态，使停用或会话撤销后已有会话尽快失效</li>
     * </ol>
     */
    @Bean
    SecurityFilterChain chain(
        HttpSecurity http,
        IdentityService identities,
        SecurityContextRepository repository,
        InternalSecurityFilter internal,
        RequestAuditWriter audits
    ) throws Exception {
        // 内部服务免用户会话和 CSRF，但仍须先经过共享密钥过滤器；浏览器写请求使用会话 CSRF。
        http.securityContext(context -> context.securityContextRepository(repository))
            .csrf(csrf ->
                csrf
                    .csrfTokenRepository(new HttpSessionCsrfTokenRepository())
                    .ignoringRequestMatchers("/internal/**")
            )
            .authorizeHttpRequests(authorization ->
                authorization
                    .requestMatchers(
                        "/internal/**",
                        "/api/health",
                        "/api/v1/auth/csrf",
                        "/api/v1/auth/login"
                    )
                    .permitAll()
                    .anyRequest()
                    .authenticated()
            )
            .exceptionHandling(exceptions ->
                exceptions
                    .authenticationEntryPoint((request, response, failure) ->
                        error(response, 401, "请先登录")
                    )
                    .accessDeniedHandler((request, response, failure) ->
                        error(response, 403, "没有权限或请求校验失败")
                    )
            )
            .logout(logout ->
                logout
                    .logoutUrl("/api/v1/auth/logout")
                    .invalidateHttpSession(true)
                    .clearAuthentication(true)
                    .logoutSuccessHandler((request, response, authentication) ->
                        ApiResponse.write(response, ApiResponse.success())
                    )
            );
        http.addFilterAfter(new RequestAuditFilter(audits), SecurityContextHolderFilter.class);
        http.addFilterBefore(internal, CsrfFilter.class);
        http.addFilterBefore(
            new OncePerRequestFilter() {
                @Override
                protected void doFilterInternal(
                    HttpServletRequest request,
                    HttpServletResponse response,
                    FilterChain chain
                ) throws ServletException, IOException {
                    var authentication = SecurityContextHolder.getContext().getAuthentication();
                    // 每次请求重新校验用户、成员关系及会话版本，使停用或会话撤销后已有会话及时失效。
                    if (
                        authentication != null &&
                        authentication.getPrincipal() instanceof Identity identity &&
                        !identities.active(identity)
                    ) {
                        SecurityContextHolder.clearContext();
                        if (request.getSession(false) != null) {
                            request.getSession(false).invalidate();
                        }
                        error(response, 401, "会话已失效");
                        return;
                    }
                    chain.doFilter(request, response);
                }
            },
            AnonymousAuthenticationFilter.class
        );
        return http.build();
    }

    static void error(HttpServletResponse response, int status, String message) throws IOException {
        response.setStatus(status);
        ApiResponse.write(response, ApiResponse.failed(status, message));
    }
}
