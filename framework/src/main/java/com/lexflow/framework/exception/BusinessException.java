package com.lexflow.framework.exception;

import lombok.Getter;

/**
 * 业务异常，携带 HTTP 状态码 + 中文提示，对标参考项目 BusinessException。
 */
@Getter
public class BusinessException extends RuntimeException {

    private final int code;

    public BusinessException(int code, String message) {
        super(message);
        this.code = code;
    }

    public static BusinessException badRequest(String msg) {
        return new BusinessException(400, msg);
    }

    public static BusinessException forbidden(String msg) {
        return new BusinessException(403, msg);
    }

    public static BusinessException notFound(String msg) {
        return new BusinessException(404, msg);
    }

    public static BusinessException conflict(String msg) {
        return new BusinessException(409, msg);
    }
}
