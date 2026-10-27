package com.lexflow.website.core.filter;

import com.lexflow.framework.model.ApiResponse;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * 内部接口鉴权过滤器：/internal/** 路由必须携带正确的 X-Internal-Secret 头。
 * 由 SecurityConfig 挂载在 Spring Security 的 CSRF 过滤器之前；禁用独立的容器注册。
 */
@Component
@Order(1)
public class InternalSecurityFilter implements Filter {

    @Value("${lexflow.internal-secret}")
    private String internalSecret;

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
        throws IOException, ServletException {
        var httpRequest = (HttpServletRequest) request;
        if (httpRequest.getRequestURI().startsWith("/internal/")) {
            String header = httpRequest.getHeader("X-Internal-Secret");
            // 限制输入长度并按字节等时比较；缺少服务端密钥时拒绝所有内部调用。
            if (
                header == null ||
                header.length() > 1024 ||
                internalSecret == null ||
                internalSecret.isBlank() ||
                !MessageDigest.isEqual(
                    header.getBytes(StandardCharsets.UTF_8),
                    internalSecret.getBytes(StandardCharsets.UTF_8)
                )
            ) {
                var result = (HttpServletResponse) response;
                result.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                ApiResponse.write(result, ApiResponse.unauthorized());
                return;
            }
        }
        chain.doFilter(request, response);
    }
}
