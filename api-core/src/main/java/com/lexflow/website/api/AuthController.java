package com.lexflow.website.api;

import com.lexflow.framework.model.ApiResponse;
import com.lexflow.service.IdentityService;
import com.lexflow.service.identity.Identity;
import com.lexflow.website.biz.dto.LoginByPasswordDTO;
import com.lexflow.website.core.filter.RequestAuditFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * 认证入口控制器，提供登录、登出、当前用户信息及 CSRF token 获取等接口。
 * <p>
 * 登录成功后必须重建会话（防会话固定攻击），登录失败与成功的审计日志
 * 由 {@link com.lexflow.website.core.filter.RequestAuditFilter} 统一记录，
 * 避免在 Controller 层混入日志逻辑。
 */
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final IdentityService identities;
    private final SecurityContextRepository repository;
    private final StringRedisTemplate redis;

    /**
     * 基于 IP 的登录频率限制脚本（Lua 原子执行）。
     * 使用单次 Lua 脚本将「计数递增」与「首次设置过期」合并为原子操作，
     * 避免 INCR + EXPIRE 之间出现进程崩溃导致 key 永不过期的竞态问题。
     * 窗口：60 秒；上限：10 次/IP。
     */
    private static final DefaultRedisScript<Long> LOGIN_ATTEMPT_LIMIT = new DefaultRedisScript<>(
        "local n=redis.call('INCR',KEYS[1]); if n==1 then redis.call('EXPIRE',KEYS[1],60) end; return n",
        Long.class
    );

    public AuthController(
        IdentityService identities,
        SecurityContextRepository repository,
        StringRedisTemplate redis
    ) {
        this.identities = identities;
        this.repository = repository;
        this.redis = redis;
    }

    /**
     * 获取当前会话的 CSRF token。
     * <p>
     * 前端在执行任何状态变更请求（POST/PUT/DELETE）前须先调用此接口取得 token，
     * 并在后续请求的 Header 中携带（Header 名由 {@code headerName} 字段指定）。
     * Spring Security 的 {@link CsrfToken} 由框架自动注入，无需手动查找。
     */
    @GetMapping("/csrf")
    ApiResponse<Map<String, String>> csrf(CsrfToken token) {
        return ApiResponse.success(
            Map.of("headerName", token.getHeaderName(), "token", token.getToken())
        );
    }

    /**
     * 密码登录接口。
     * <p>
     * 流程：IP 限流校验 → 身份认证 → 会话重建 → SecurityContext 持久化 → 审计属性标记。
     *
     * @param loginRequest 包含 tenantId、username、password 的请求体，字段级校验由 Bean Validation 完成
     * @param request      用于读取客户端 IP（限流）、重建 Session
     * @param response     用于将新 Session ID 写回 Cookie
     */
    @PostMapping("/login")
    ApiResponse<Map<String, String>> login(
        @Valid @RequestBody LoginByPasswordDTO loginRequest,
        HttpServletRequest request,
        HttpServletResponse response
    ) {
        // 基于 IP 的滑动窗口限流：同一 IP 60 秒内超过 10 次即拒绝，防止暴力破解。
        Long count = redis.execute(
            LOGIN_ATTEMPT_LIMIT,
            List.of("lexflow:login:ip:" + request.getRemoteAddr())
        );
        if (count == null || count > 10) {
            throw new ResponseStatusException(
                HttpStatus.TOO_MANY_REQUESTS,
                "尝试过于频繁，请稍后重试"
            );
        }

        // 委托 IdentityService 执行密码比对与租户校验；认证失败统一返回 401，
        // 不区分「用户不存在」与「密码错误」，避免用户名枚举攻击。
        Identity identity = identities
            .authenticate(loginRequest.username(), loginRequest.password(), loginRequest.tenantId())
            .orElseThrow(() ->
                new ResponseStatusException(HttpStatus.UNAUTHORIZED, "用户名、密码或律所信息错误")
            );

        // 防会话固定攻击（Session Fixation）：登录成功后立即废弃旧 Session，
        // 强制分配新 Session ID，使攻击者预先植入的 Session 失效。
        // 前端在登录前后需同步清除 CSRF token 缓存，否则新会话的 CSRF 校验会失败。
        if (request.getSession(false) != null) {
            request.getSession(false).invalidate();
        }
        request.getSession(true);

        // 构建 SecurityContext 并通过 Repository 持久化到新 Session，
        // 确保同一请求后续的鉴权过滤器能读取到已登录身份。
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(
            UsernamePasswordAuthenticationToken.authenticated(identity, null, List.of())
        );
        SecurityContextHolder.setContext(context);
        repository.saveContext(context, request, response);

        // 将登录身份写入请求属性，供 RequestAuditFilter 在响应阶段提取并记录审计日志。
        request.setAttribute(RequestAuditFilter.LOGIN_IDENTITY, identity);
        return ApiResponse.success(view(identity));
    }

    /**
     * 获取当前登录用户的身份信息。
     * <p>
     * 前端刷新页面时调用此接口以会话为准恢复身份状态，避免将身份持久化到
     * localStorage 导致过期凭据残留。未登录时 Spring Security 会直接返回 401。
     */
    @GetMapping("/me")
    ApiResponse<Map<String, String>> me(@AuthenticationPrincipal Identity identity) {
        return ApiResponse.success(view(identity));
    }

    /**
     * 将内部 {@link Identity} 转换为前端所需的视图字段。
     * <p>
     * 仅暴露 id、tenantId、name，避免将内部权限标记或敏感字段泄露给客户端。
     */
    private Map<String, String> view(Identity identity) {
        return Map.of(
            "id",
            Long.toString(identity.userId()),
            "tenantId",
            Long.toString(identity.tenantId()),
            "name",
            identity.name()
        );
    }
}
