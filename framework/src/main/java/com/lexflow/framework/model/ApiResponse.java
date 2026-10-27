package com.lexflow.framework.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lexflow.framework.context.TraceContext;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;

/**
 * 统一 API 响应；静态工厂将当前追踪标识写入响应，方便关联请求与服务端日志。
 */
@Slf4j
@Data
public class ApiResponse<T> {

    private Integer code;
    private String message;
    private T data;
    private String traceId;
    private static final ObjectMapper JSON = new ObjectMapper();

    private ApiResponse() {}

    public static <T> ApiResponse<T> success() {
        return success(null);
    }

    public static <T> ApiResponse<T> success(T data) {
        ApiResponse<T> response = new ApiResponse<>();
        response.code = 200;
        response.message = "成功";
        response.data = data;
        response.traceId = TraceContext.currentOrNew();
        return response;
    }

    public static <T> ApiResponse<T> failed(int code, String message) {
        ApiResponse<T> response = new ApiResponse<>();
        response.code = code;
        response.message = message;
        response.data = null;
        response.traceId = TraceContext.currentOrNew();
        return response;
    }

    public static <T> ApiResponse<T> badRequest(String message) {
        return failed(400, message);
    }

    public static <T> ApiResponse<T> unauthorized() {
        return failed(401, "未登录或会话已过期");
    }

    public static <T> ApiResponse<T> forbidden() {
        return failed(403, "无权限执行此操作");
    }

    public static <T> ApiResponse<T> forbidden(String message) {
        return failed(403, message);
    }

    public static <T> ApiResponse<T> notFound(String message) {
        return failed(404, message);
    }

    public static <T> ApiResponse<T> serverError() {
        return failed(500, "服务器内部错误");
    }

    /** 直接向 HttpServletResponse 写出 JSON（Security 过滤器层使用） */
    public static void write(HttpServletResponse response, ApiResponse<?> body) {
        try {
            response.setHeader("content-type", "application/json;charset=UTF-8");
            response.setHeader("X-Trace-Id", body.getTraceId());
            String json = JSON.writeValueAsString(body);
            response.getOutputStream().write(json.getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            log.error("write ApiResponse failed", e);
        }
    }
}
