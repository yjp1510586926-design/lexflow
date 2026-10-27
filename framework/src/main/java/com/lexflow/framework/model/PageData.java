package com.lexflow.framework.model;

import java.io.Serializable;
import java.util.Collections;
import java.util.List;
import lombok.Data;

/**
 * 统一分页结果，对标参考项目 PageData。
 */
@Data
public class PageData<T> implements Serializable {

    private long page;
    private long capacity;
    private long total;
    private List<T> records;

    public PageData(long page, long capacity) {
        this.page = page;
        this.capacity = capacity;
    }

    public static <T> PageData<T> empty() {
        PageData<T> pageData = new PageData<>(1, 20);
        pageData.total = 0;
        pageData.records = Collections.emptyList();
        return pageData;
    }

    public long getCapacity() {
        return capacity <= 0 ? 20 : capacity;
    }

    public long getPageCount() {
        long capacity = getCapacity();
        if (total == 0) {
            return 1;
        }
        return total % capacity == 0 ? total / capacity : total / capacity + 1;
    }
}
