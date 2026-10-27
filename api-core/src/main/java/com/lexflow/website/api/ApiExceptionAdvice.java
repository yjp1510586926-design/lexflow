package com.lexflow.website.api;

import com.lexflow.framework.model.ApiResponse;
import com.lexflow.service.permission.AccessDenied;
import jakarta.validation.ConstraintViolationException;
import java.util.ConcurrentModificationException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * api-core 层异常处理（补充 service 层未覆盖的 Spring MVC 异常）。
 * 与 framework GlobalExceptionAdvice 共存，此类具有更高优先级（更具体的异常类型）。
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class ApiExceptionAdvice {

    @ExceptionHandler({
        HttpMessageNotReadableException.class,
        MethodArgumentTypeMismatchException.class,
        HandlerMethodValidationException.class,
    })
    ResponseEntity<ApiResponse<?>> malformed() {
        return ResponseEntity.badRequest().body(ApiResponse.badRequest("请求参数或内容格式不正确"));
    }

    @ExceptionHandler(NoResourceFoundException.class)
    ResponseEntity<ApiResponse<?>> missing() {
        return ResponseEntity.status(404).body(ApiResponse.notFound("请求的资源不存在"));
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    ResponseEntity<ApiResponse<?>> method() {
        return ResponseEntity.status(405).body(ApiResponse.failed(405, "请求方法不支持"));
    }

    @ExceptionHandler(ResponseStatusException.class)
    ResponseEntity<ApiResponse<?>> status(ResponseStatusException exception) {
        return ResponseEntity.status(exception.getStatusCode()).body(
            ApiResponse.failed(
                exception.getStatusCode().value(),
                exception.getReason() != null ? exception.getReason() : "请求错误"
            )
        );
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ApiResponse<?>> validation(MethodArgumentNotValidException exception) {
        String message = exception
            .getBindingResult()
            .getFieldErrors()
            .stream()
            .map(fieldError -> fieldError.getDefaultMessage())
            .findFirst()
            .orElse("请求参数不正确");
        return ResponseEntity.badRequest().body(ApiResponse.badRequest(message));
    }

    @ExceptionHandler(AccessDenied.class)
    ResponseEntity<ApiResponse<?>> denied() {
        return ResponseEntity.status(403).body(ApiResponse.forbidden());
    }

    @ExceptionHandler(ConcurrentModificationException.class)
    ResponseEntity<ApiResponse<?>> stale() {
        return ResponseEntity.status(409).body(
            ApiResponse.failed(409, "数据已被修改，请刷新后重试")
        );
    }

    @ExceptionHandler({ IllegalArgumentException.class, ConstraintViolationException.class })
    ResponseEntity<ApiResponse<?>> invalid(Exception exception) {
        return ResponseEntity.badRequest().body(
            ApiResponse.badRequest(
                exception.getMessage() != null ? exception.getMessage() : "请求参数或业务状态不正确"
            )
        );
    }

    @ExceptionHandler(IllegalStateException.class)
    ResponseEntity<ApiResponse<?>> state(IllegalStateException exception) {
        return ResponseEntity.badRequest().body(
            ApiResponse.badRequest(
                exception.getMessage() != null ? exception.getMessage() : "当前业务状态不允许此操作"
            )
        );
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<ApiResponse<?>> conflict() {
        return ResponseEntity.status(409).body(
            ApiResponse.failed(409, "数据已存在或关联关系不正确")
        );
    }
}
