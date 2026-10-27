package com.lexflow.framework.model;

import lombok.Data;

/**
 * 分页请求封装
 */
@Data
public class PageWrap {

    private int page = 1;
    private int capacity = 20;

    public int getOffset() {
        return (Math.max(page, 1) - 1) * getCapacity();
    }

    public int getCapacity() {
        return capacity <= 0 ? 20 : Math.min(capacity, 200);
    }
}
