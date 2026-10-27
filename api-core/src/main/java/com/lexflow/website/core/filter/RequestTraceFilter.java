package com.lexflow.website.core.filter;

import com.lexflow.framework.context.TraceContext;
import com.lexflow.framework.model.ApiResponse;
import com.lexflow.service.audit.RequestAuditWriter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 请求追踪过滤器：在整个过滤器链最外层为每次请求分配 TraceId，并将其写入响应头 {@code X-Trace-Id}。
 * <p>
 * 排在 Spring Security 之前（{@code HIGHEST_PRECEDENCE + 10}），确保无论鉴权是否通过，
 * 每个请求都有可跟踪的 TraceId。同时作为兜底异常处理层：
 * 当内层过滤器或 DispatcherServlet 抛出未捕获异常时，在此统一写回 500 响应并尝试记录审计，
 * 防止异常直接透出到 Servlet 容器导致无结构化错误信息。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class RequestTraceFilter extends OncePerRequestFilter {

    private static final Logger LOG = LoggerFactory.getLogger(RequestTraceFilter.class);
    private final RequestAuditWriter audits;

    public RequestTraceFilter(RequestAuditWriter audits) {
        this.audits = audits;
    }

    @Override
    protected void doFilterInternal(
        HttpServletRequest request,
        HttpServletResponse response,
        FilterChain chain
    ) throws ServletException, IOException {
        try (var scope = TraceContext.startRequest()) {
            response.setHeader("X-Trace-Id", scope.traceId());
            try {
                chain.doFilter(request, response);
            } catch (ServletException | IOException | RuntimeException failure) {
                LOG.error(
                    "Unhandled request failure type={} traceId={}",
                    failure.getClass().getSimpleName(),
                    scope.traceId()
                );
                String path = request.getRequestURI();
                if (
                    (path.startsWith("/api/v1/") || path.startsWith("/internal/")) &&
                    !Boolean.TRUE.equals(request.getAttribute(RequestAuditFilter.ATTEMPTED))
                ) {
                    request.setAttribute(RequestAuditFilter.ATTEMPTED, Boolean.TRUE);
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
                    try {
                        // 身份尚未验证便失败的请求记为匿名，不能用请求头或请求体中的租户信息归属审计。
                        var outcome = audits.record(
                            null,
                            "request:failure",
                            500,
                            method,
                            "UNMATCHED",
                            null,
                            scope.traceId()
                        );
                        LOG.info(
                            "Independent request audit persistence={} action=request:failure traceId={}",
                            outcome,
                            scope.traceId()
                        );
                    } catch (RuntimeException unavailable) {
                        LOG.error(
                            "Independent request audit unavailable action=request:failure traceId={} status=500",
                            scope.traceId()
                        );
                    }
                }
                if (response.isCommitted()) {
                    throw failure;
                }
                response.resetBuffer();
                response.setStatus(500);
                ApiResponse.write(response, ApiResponse.serverError());
            }
        }
    }
}
