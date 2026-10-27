package com.lexflow.website.core.filter;

import com.lexflow.framework.context.TraceContext;
import com.lexflow.service.audit.RequestAuditWriter;
import com.lexflow.service.identity.Identity;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerMapping;

/** 在安全上下文加载后、CSRF 和退出处理前执行，以记录失败请求及登录、退出操作。 */
public class RequestAuditFilter extends OncePerRequestFilter {

    private static final Logger LOG = LoggerFactory.getLogger(RequestAuditFilter.class);
    // 登录接口仅在认证成功后写入此属性，供审计取用已验证的新身份。
    public static final String LOGIN_IDENTITY =
        RequestAuditFilter.class.getName() + ".loginIdentity";
    // 外层异常过滤器据此避免重复尝试审计，即使本次持久化自身失败也不另建事件。
    public static final String ATTEMPTED = RequestAuditFilter.class.getName() + ".attempted";
    private final RequestAuditWriter audits;

    public RequestAuditFilter(RequestAuditWriter audits) {
        this.audits = audits;
    }

    @Override
    protected void doFilterInternal(
        HttpServletRequest request,
        HttpServletResponse response,
        FilterChain chain
    ) throws ServletException, IOException {
        if (
            !request.getRequestURI().startsWith("/api/v1/") &&
            !request.getRequestURI().startsWith("/internal/")
        ) {
            chain.doFilter(request, response);
            return;
        }
        // 先保留已认证身份，退出处理清空安全上下文后仍可记录真正的操作者。
        Identity initial = null;
        boolean threw = false;
        try {
            var auth = SecurityContextHolder.getContext().getAuthentication();
            if (auth != null && auth.getPrincipal() instanceof Identity actor) {
                initial = actor;
            }
            chain.doFilter(request, response);
        } catch (IOException | ServletException | RuntimeException failure) {
            threw = true;
            throw failure;
        } finally {
            int status = threw ? 500 : response.getStatus();
            boolean login =
                request.getRequestURI().equals("/api/v1/auth/login") &&
                request.getMethod().equals("POST");
            boolean logout =
                request.getRequestURI().equals("/api/v1/auth/logout") &&
                request.getMethod().equals("POST");
            if (status >= 400 || login || logout) {
                Identity actor =
                    request.getAttribute(LOGIN_IDENTITY) instanceof Identity verified
                        ? verified
                        : initial;
                // 登录失败时不使用输入的用户名或租户归属审计，避免把未经验证的身份当作操作者。
                if (login && status >= 400) {
                    actor = null;
                }
                String action = login ? "auth:login" : logout ? "auth:logout" : "request:failure";
                String method = Set.of(
                    "GET",
                    "POST",
                    "PUT",
                    "PATCH",
                    "DELETE",
                    "HEAD",
                    "OPTIONS"
                ).contains(request.getMethod())
                    ? request.getMethod()
                    : "OTHER";
                String route = login
                    ? "/api/v1/auth/login"
                    : logout
                      ? "/api/v1/auth/logout"
                      : route(request);
                String trace = TraceContext.currentOrNew();
                request.setAttribute(ATTEMPTED, Boolean.TRUE);
                try {
                    var outcome = audits.record(
                        actor,
                        action,
                        status,
                        method,
                        route,
                        objectId(request),
                        trace
                    );
                    LOG.info(
                        "Independent request audit persistence={} action={} traceId={}",
                        outcome,
                        action,
                        trace
                    );
                } catch (RuntimeException unavailable) {
                    // 审计不可用时保留原始业务或安全响应，只输出不含请求内容的运维信号。
                    LOG.error(
                        "Independent request audit unavailable action={} traceId={} status={}",
                        action,
                        trace,
                        status
                    );
                }
            }
        }
    }

    /** 只记录服务端匹配的路由模板，不保存原始 URL，避免路径正文或查询参数进入审计。 */
    private static String route(HttpServletRequest request) {
        Object value = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        if (
            value instanceof String pattern &&
            pattern.length() <= 200 &&
            pattern.matches("[/a-zA-Z0-9_{}:*.-]+")
        ) {
            return pattern;
        }
        return "UNMATCHED";
    }

    private static String objectId(HttpServletRequest request) {
        Object value = request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
        if (value instanceof Map<?, ?> variables) {
            for (String key : new String[] {
                "id",
                "fileId",
                "fileVersionId",
                "taskId",
                "caseId",
                "baseId",
            }) {
                Object id = variables.get(key);
                if (id instanceof String text && text.matches("[0-9]{1,19}")) {
                    return text;
                }
            }
        }
        return null;
    }
}
