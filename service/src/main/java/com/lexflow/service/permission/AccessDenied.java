package com.lexflow.service.permission;

public class AccessDenied extends RuntimeException {

    public AccessDenied() {
        super("没有权限访问该资源");
    }
}
