package com.lexflow.framework.exception;

import com.lexflow.framework.model.ApiResponse;
import jakarta.servlet.http.HttpServletResponse;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 统一业务错误与参数校验响应；未知异常仅在服务端记录详情，接口返回通用错误。
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionAdvice {

    @ExceptionHandler(BusinessException.class)
    public void handleBusiness(BusinessException e, HttpServletResponse response) {
        log.warn("[BizEx] code={} msg={}", e.getCode(), e.getMessage());
        response.setStatus(e.getCode() >= 400 && e.getCode() <= 599 ? e.getCode() : 400);
        ApiResponse.write(response, ApiResponse.failed(e.getCode(), e.getMessage()));
    }

    @ResponseStatus(HttpStatus.BAD_REQUEST)
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ApiResponse<?> handleValidation(MethodArgumentNotValidException e) {
        String msg = e
            .getBindingResult()
            .getFieldErrors()
            .stream()
            .map(FieldError::getDefaultMessage)
            .collect(Collectors.joining("; "));
        return ApiResponse.badRequest(msg);
    }

    @ExceptionHandler(Throwable.class)
    public void handleAll(Throwable e, HttpServletResponse response) {
        log.error("[UnhandledEx]", e);
        response.setStatus(500);
        ApiResponse.write(response, ApiResponse.serverError());
    }
}
