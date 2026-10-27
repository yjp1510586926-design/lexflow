package com.lexflow.website.core.client;

import java.net.URI;
import java.util.Locale;
import java.util.Set;

/** 部署配置的内部服务地址：HTTP 仅限本机或 Compose 服务名；外部模型地址另行执行 HTTPS 策略。 */
public final class InternalAiServiceUrl {

    private static final Set<String> HTTP_HOSTS = Set.of(
        "127.0.0.1",
        "localhost",
        "::1",
        "[::1]",
        "ai-service"
    );

    private InternalAiServiceUrl() {}

    /** 此地址来自部署配置，不能由用户输入替代；调用客户端还须禁用重定向，避免转发共享密钥。 */
    public static URI parse(String value) {
        URI uri = URI.create(value);
        String host = uri.getHost();
        boolean trustedHttp = host != null && HTTP_HOSTS.contains(host.toLowerCase(Locale.ROOT));
        if (
            host == null ||
            uri.getUserInfo() != null ||
            uri.getQuery() != null ||
            uri.getFragment() != null ||
            !("https".equals(uri.getScheme()) || ("http".equals(uri.getScheme()) && trustedHttp))
        ) {
            throw new IllegalArgumentException(
                "AI 内部地址须为 HTTPS、本机 HTTP 或 Compose 的 ai-service HTTP"
            );
        }
        return uri;
    }
}
