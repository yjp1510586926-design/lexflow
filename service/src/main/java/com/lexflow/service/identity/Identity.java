package com.lexflow.service.identity;

import java.io.Serializable;

/**
 * 已认证主体保存在会话中的身份快照；sessionVersion 用于识别已撤销的旧会话。
 * 快照不代表当前权限，业务服务仍须复查用户状态、租户成员关系及操作授权。
 */
public record Identity(
    long userId,
    long tenantId,
    long sessionVersion,
    String name
) implements Serializable {}
