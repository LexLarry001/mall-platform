package com.mall.product.category;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mall.common.exception.BizException;
import com.mall.common.result.ResultCode;
import com.mall.product.category.dto.CategoryNode;
import com.mall.product.entity.CategoryEntity;
import com.mall.product.entity.ProductEntity;
import com.mall.product.mapper.CategoryMapper;
import com.mall.product.mapper.ProductMapper;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 平台类目树：买家导航读缓存，平台管理写操作后统一失效。
 *
 * <p>缓存策略（plan RF5）：买家树以 JSON 存 {@code mall:category:tree}，不设 TTL，
 * 所有写操作（新增/编辑/状态变更/删除）后删除该键，由下一次买家查询重建；
 * 缓存内容损坏时按 miss 处理查库重建，不阻断主流程。</p>
 *
 * <p>树语义：买家树仅含启用类目，父类目被停用时其子类目不连带展示（避免停用的
 * 子树被提升到顶层）；管理端树全量展示，父节点缺失的孤儿类目提升到顶层以便运营修复。</p>
 */
@Service
public class CategoryService {

    /** 买家类目树缓存键。 */
    public static final String CACHE_KEY_TREE = "mall:category:tree";

    private static final int STATUS_ENABLED = 1;
    private static final int NAME_MAX_LENGTH = 32;

    private final CategoryMapper categoryMapper;
    private final ProductMapper productMapper;
    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;

    public CategoryService(CategoryMapper categoryMapper, ProductMapper productMapper,
                           StringRedisTemplate redis, ObjectMapper objectMapper) {
        this.categoryMapper = categoryMapper;
        this.productMapper = productMapper;
        this.redis = redis;
        this.objectMapper = objectMapper;
    }

    /** 买家类目树：仅启用类目，先读缓存，miss 查库回写。 */
    public List<CategoryNode> buyerTree() {
        String cached = redis.opsForValue().get(CACHE_KEY_TREE);
        if (StringUtils.hasText(cached)) {
            try {
                return objectMapper.readValue(cached, new TypeReference<>() {
                });
            } catch (JsonProcessingException e) {
                // 缓存内容损坏按 miss 处理，查库重建
            }
        }
        List<CategoryEntity> entities = categoryMapper.selectList(Wrappers.<CategoryEntity>lambdaQuery()
                .eq(CategoryEntity::getStatus, STATUS_ENABLED));
        List<CategoryNode> tree = buildTree(entities, false);
        try {
            redis.opsForValue().set(CACHE_KEY_TREE, objectMapper.writeValueAsString(tree));
        } catch (JsonProcessingException e) {
            // 序列化失败仅放弃本次缓存写入，不影响查询结果
        }
        return tree;
    }

    /** 平台管理树：全量含停用，不走缓存。 */
    public List<CategoryNode> adminTree() {
        return buildTree(categoryMapper.selectList(null), true);
    }

    /** 新增类目（默认启用），写后失效缓存。 */
    public Long create(Long parentId, String name, Integer sort) {
        long pid = parentId == null ? 0L : parentId;
        if (pid != 0L && categoryMapper.selectById(pid) == null) {
            throw new BizException("父类目不存在");
        }
        CategoryEntity entity = new CategoryEntity();
        entity.setParentId(pid);
        entity.setName(validatedName(name));
        entity.setSort(sort == null ? 0 : sort);
        entity.setStatus(STATUS_ENABLED);
        categoryMapper.insert(entity);
        evictCache();
        return entity.getId();
    }

    /** 编辑类目（仅名称与排序），写后失效缓存。 */
    public void update(Long id, String name, Integer sort) {
        CategoryEntity entity = requireCategory(id);
        entity.setName(validatedName(name));
        if (sort != null) {
            entity.setSort(sort);
        }
        categoryMapper.updateById(entity);
        evictCache();
    }

    /** 启用（1）/停用（0），写后失效缓存。 */
    public void changeStatus(Long id, Integer status) {
        if (status == null || (status != 0 && status != 1)) {
            throw new BizException(ResultCode.PARAM_INVALID.getCode(), "状态仅支持 0（停用）/1（启用）");
        }
        CategoryEntity entity = requireCategory(id);
        entity.setStatus(status);
        categoryMapper.updateById(entity);
        evictCache();
    }

    /**
     * 删除类目（逻辑删除），写后失效缓存。
     *
     * <p>删除保护（plan RF4）：有子类目或有商品引用时拒绝删除。</p>
     */
    public void delete(Long id) {
        requireCategory(id);
        Long children = categoryMapper.selectCount(Wrappers.<CategoryEntity>lambdaQuery()
                .eq(CategoryEntity::getParentId, id));
        if (children > 0) {
            throw new BizException("请先删除该类目下的子类目");
        }
        Long refs = productMapper.selectCount(Wrappers.<ProductEntity>lambdaQuery()
                .eq(ProductEntity::getCategoryId, id));
        if (refs > 0) {
            throw new BizException("该类目下存在商品，无法删除");
        }
        categoryMapper.deleteById(id);
        evictCache();
    }

    private CategoryEntity requireCategory(Long id) {
        CategoryEntity entity = categoryMapper.selectById(id);
        if (entity == null) {
            throw new BizException("类目不存在");
        }
        return entity;
    }

    private String validatedName(String name) {
        if (name == null || name.isBlank()) {
            throw new BizException(ResultCode.PARAM_INVALID.getCode(), "类目名称不能为空");
        }
        String trimmed = name.trim();
        if (trimmed.length() > NAME_MAX_LENGTH) {
            throw new BizException(ResultCode.PARAM_INVALID.getCode(), "类目名称不能超过 32 个字符");
        }
        return trimmed;
    }

    private void evictCache() {
        redis.delete(CACHE_KEY_TREE);
    }

    /**
     * 组树：同级按 sort 升序（稳定排序，同值保持原顺序）。
     *
     * @param promoteOrphans 父节点不在集合中时，true 提升到顶层（管理端），false 隐藏（买家端）
     */
    private List<CategoryNode> buildTree(List<CategoryEntity> entities, boolean promoteOrphans) {
        List<CategoryNode> nodes = entities.stream()
                .map(CategoryNode::from)
                .sorted(Comparator.comparing(node -> node.sort() == null ? 0 : node.sort()))
                .collect(Collectors.toCollection(ArrayList::new));
        Map<Long, CategoryNode> byId = nodes.stream()
                .collect(Collectors.toMap(CategoryNode::id, Function.identity(), (a, b) -> a,
                        LinkedHashMap::new));
        List<CategoryNode> roots = new ArrayList<>();
        for (CategoryNode node : nodes) {
            long parentId = node.parentId() == null ? 0L : node.parentId();
            CategoryNode parent = parentId == 0L ? null : byId.get(parentId);
            if (parent != null) {
                parent.children().add(node);
            } else if (parentId == 0L || promoteOrphans) {
                roots.add(node);
            }
        }
        return roots;
    }
}
