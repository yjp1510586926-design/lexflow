package com.lexflow.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.lexflow.framework.exception.BusinessException;
import com.lexflow.framework.model.BaseModel;
import com.lexflow.framework.model.PageData;
import com.lexflow.framework.utils.PageUtil;
import java.lang.reflect.ParameterizedType;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.transaction.annotation.Transactional;

/**
 * 提供 CRUD、分页与计数等通用持久化能力。
 * 这些方法不检查身份、租户或资源权限；业务入口须完成授权并为查询提供租户条件。
 *
 * @param <Model>  实体类型，必须继承 BaseModel
 * @param <Mapper> Mapper 类型，必须继承 BaseMapper<Model>
 */
@Slf4j
public abstract class BaseService<Model extends BaseModel, Mapper extends BaseMapper<Model>> {

    protected final Mapper mapper;
    private final Class<Model> modelClass;

    @SuppressWarnings("unchecked")
    protected BaseService(Mapper mapper) {
        this.mapper = mapper;
        this.modelClass = (Class<Model>) (
            (ParameterizedType) getClass().getGenericSuperclass()
        ).getActualTypeArguments()[0];
    }

    // ── 创建 ───────────────────────────────────────────────────────────────────

    public Long create(Model model) {
        mapper.insert(model);
        return model.getId();
    }

    @Transactional
    public List<Long> createInBatch(Collection<Model> models) {
        List<Long> ids = new ArrayList<>();
        for (Model model : models) {
            ids.add(create(model));
        }
        return ids;
    }

    // ── 更新 ───────────────────────────────────────────────────────────────────

    public int updateById(Model model) {
        return mapper.updateById(model);
    }

    // ── 删除（物理删除） ────────────────────────────────────────────────────────

    public int deleteById(Long id) {
        return mapper.deleteById(id);
    }

    // ── 查询 ───────────────────────────────────────────────────────────────────

    public Model getById(Long id) {
        return mapper.selectById(id);
    }

    /** 按 id 获取，不存在抛 404 */
    public Model getByIdOrThrow(Long id, String entityName) {
        Model model = mapper.selectById(id);
        if (model == null) {
            throw BusinessException.notFound(entityName + " 不存在");
        }
        return model;
    }

    public Model findOne(QueryWrapper<Model> query) {
        query.setEntityClass(modelClass);
        return mapper.selectOne(query);
    }

    public Model findOne(LambdaQueryWrapper<Model> query) {
        return mapper.selectOne(query);
    }

    public List<Model> findList(QueryWrapper<Model> query) {
        query.setEntityClass(modelClass);
        return mapper.selectList(query);
    }

    public List<Model> findList(LambdaQueryWrapper<Model> query) {
        return mapper.selectList(query);
    }

    public List<Model> findAll() {
        return mapper.selectList(new QueryWrapper<Model>().setEntityClass(modelClass));
    }

    // ── 分页 ───────────────────────────────────────────────────────────────────

    public PageData<Model> findPage(int page, int capacity, LambdaQueryWrapper<Model> query) {
        IPage<Model> result = mapper.selectPage(new Page<>(page, capacity), query);
        return PageUtil.from(result);
    }

    public PageData<Model> findPage(int page, int capacity, QueryWrapper<Model> query) {
        query.setEntityClass(modelClass);
        IPage<Model> result = mapper.selectPage(new Page<>(page, capacity), query);
        return PageUtil.from(result);
    }

    // ── 计数 ───────────────────────────────────────────────────────────────────

    public long count(LambdaQueryWrapper<Model> query) {
        return mapper.selectCount(query);
    }

    public long count(QueryWrapper<Model> query) {
        query.setEntityClass(modelClass);
        return mapper.selectCount(query);
    }

    // ── 存在性检查 ─────────────────────────────────────────────────────────────

    public boolean exists(LambdaQueryWrapper<Model> query) {
        return mapper.selectCount(query) > 0;
    }
}
