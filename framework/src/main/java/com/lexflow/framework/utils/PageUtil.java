package com.lexflow.framework.utils;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.lexflow.framework.model.PageData;

/**
 * MyBatis-Plus 分页结果转换工具（参考项目 PageUtil 风格）。
 */
public class PageUtil {

    public static <T> PageData<T> from(IPage<T> page) {
        PageData<T> pageData = new PageData<>(page.getCurrent(), page.getSize());
        pageData.setTotal(page.getTotal());
        pageData.setRecords(page.getRecords());
        return pageData;
    }
}
