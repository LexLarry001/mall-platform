package com.mall.product.category;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mall.common.exception.BizException;
import com.mall.product.category.dto.CategoryNode;
import com.mall.product.entity.CategoryEntity;
import com.mall.product.mapper.CategoryMapper;
import com.mall.product.mapper.ProductMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 类目树组树、缓存与删除保护单测（plan Task 5 Step 1 / RF4 / RF5）。
 */
@ExtendWith(MockitoExtension.class)
class CategoryServiceTest {

    @Mock
    private CategoryMapper categoryMapper;
    @Mock
    private ProductMapper productMapper;
    @Mock
    private StringRedisTemplate redis;
    @Mock
    private ValueOperations<String, String> valueOperations;

    private CategoryService categoryService;

    @BeforeEach
    void setUp() {
        lenient().when(redis.opsForValue()).thenReturn(valueOperations);
        categoryService = new CategoryService(categoryMapper, productMapper, redis, new ObjectMapper());
    }

    /** 组树：乱序输入按 sort 升序嵌套到正确父节点下。 */
    @Test
    void admin_tree_groups_unordered_input_by_sort() {
        when(categoryMapper.selectList(isNull())).thenReturn(List.of(
                entity(4L, 2L, "子类目B", 2, 1),
                entity(1L, 0L, "根类目一", 2, 1),
                entity(3L, 2L, "子类目A", 1, 1),
                entity(2L, 0L, "根类目二", 1, 0),
                entity(5L, 99L, "孤儿类目", 1, 1)));

        List<CategoryNode> tree = categoryService.adminTree();

        // 全部节点先按 sort 升序（稳定排序，同值保持输入顺序），再挂到父节点；
        // 根：根类目二(1) → 孤儿类目(1) → 根类目一(2)；父节点缺失的孤儿提升到根（管理端可见以便修复）
        assertThat(tree).extracting(CategoryNode::name)
                .containsExactly("根类目二", "孤儿类目", "根类目一");
        CategoryNode root2 = tree.get(0);
        assertThat(root2.children()).extracting(CategoryNode::name)
                .containsExactly("子类目A", "子类目B");
        assertThat(tree.get(2).children()).isEmpty();
    }

    /** 买家树：缓存命中时不查库。 */
    @Test
    void buyer_tree_cache_hit_skips_db() {
        when(valueOperations.get("mall:category:tree")).thenReturn(
                "[{\"id\":1,\"parentId\":0,\"name\":\"缓存树\",\"sort\":1,\"status\":1,\"children\":[]}]");

        List<CategoryNode> tree = categoryService.buyerTree();

        assertThat(tree).hasSize(1);
        assertThat(tree.get(0).name()).isEqualTo("缓存树");
        verify(categoryMapper, never()).selectList(any(Wrapper.class));
    }

    /** 买家树：缓存 miss 查库并回写；父节点不在启用集合中的节点隐藏（停用父类目不连带展示子类目）。 */
    @Test
    void buyer_tree_cache_miss_queries_db_and_writes_back() {
        when(valueOperations.get("mall:category:tree")).thenReturn(null);
        when(categoryMapper.selectList(any(Wrapper.class))).thenReturn(List.of(
                entity(2L, 1L, "子类目", 1, 1),
                entity(1L, 0L, "根类目", 1, 1),
                entity(3L, 88L, "父已停用", 1, 1)));

        List<CategoryNode> tree = categoryService.buyerTree();

        assertThat(tree).hasSize(1);
        assertThat(tree.get(0).name()).isEqualTo("根类目");
        assertThat(tree.get(0).children()).extracting(CategoryNode::name).containsExactly("子类目");
        verify(valueOperations).set(eq("mall:category:tree"), anyString());
    }

    /** 新增成功：默认启用、parentId 缺省为 0，写后删除缓存（RF5）。 */
    @Test
    void create_inserts_enabled_and_evicts_cache() {
        when(categoryMapper.insert(any(CategoryEntity.class))).thenReturn(1);

        categoryService.create(null, " 新类目 ", null);

        ArgumentCaptor<CategoryEntity> captor = ArgumentCaptor.forClass(CategoryEntity.class);
        verify(categoryMapper).insert(captor.capture());
        assertThat(captor.getValue().getParentId()).isZero();
        assertThat(captor.getValue().getName()).isEqualTo("新类目");
        assertThat(captor.getValue().getSort()).isZero();
        assertThat(captor.getValue().getStatus()).isEqualTo(1);
        verify(redis).delete("mall:category:tree");
    }

    /** 新增：父类目不存在 → 业务错误，不落库不删缓存。 */
    @Test
    void create_with_missing_parent_rejected() {
        when(categoryMapper.selectById(99L)).thenReturn(null);

        assertThatThrownBy(() -> categoryService.create(99L, "子类目", 1))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("父类目不存在");
        verify(categoryMapper, never()).insert(any(CategoryEntity.class));
        verify(redis, never()).delete(anyString());
    }

    /** 新增/编辑：名称为空或超长 → 400。 */
    @Test
    void create_blank_or_too_long_name_rejected() {
        assertThatThrownBy(() -> categoryService.create(null, "  ", 1))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("类目名称不能为空");
        assertThatThrownBy(() -> categoryService.create(null, "x".repeat(33), 1))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("不能超过 32");
    }

    /** 编辑：仅改名称与排序，写后删除缓存（RF5）。 */
    @Test
    void update_changes_name_and_sort_then_evicts_cache() {
        CategoryEntity existing = entity(1L, 0L, "旧名", 1, 1);
        when(categoryMapper.selectById(1L)).thenReturn(existing);

        categoryService.update(1L, "新名", 9);

        ArgumentCaptor<CategoryEntity> captor = ArgumentCaptor.forClass(CategoryEntity.class);
        verify(categoryMapper).updateById(captor.capture());
        assertThat(captor.getValue().getName()).isEqualTo("新名");
        assertThat(captor.getValue().getSort()).isEqualTo(9);
        assertThat(captor.getValue().getStatus()).isEqualTo(1);
        verify(redis).delete("mall:category:tree");
    }

    /** 编辑：类目不存在 → 业务错误。 */
    @Test
    void update_missing_category_rejected() {
        when(categoryMapper.selectById(1L)).thenReturn(null);

        assertThatThrownBy(() -> categoryService.update(1L, "新名", 1))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("类目不存在");
        verify(redis, never()).delete(anyString());
    }

    /** 状态变更：非法值 400 不落库；合法值落库并删缓存（RF5）。 */
    @Test
    void change_status_validates_value_and_evicts_cache() {
        CategoryEntity existing = entity(1L, 0L, "类目", 1, 1);
        when(categoryMapper.selectById(1L)).thenReturn(existing);

        assertThatThrownBy(() -> categoryService.changeStatus(1L, 2))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("状态仅支持");

        categoryService.changeStatus(1L, 0);

        ArgumentCaptor<CategoryEntity> captor = ArgumentCaptor.forClass(CategoryEntity.class);
        verify(categoryMapper).updateById(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(0);
        verify(redis).delete("mall:category:tree");
    }

    /** 删除保护（RF4）：有子类目 → 业务错误，不得真删。 */
    @Test
    void delete_with_children_rejected() {
        when(categoryMapper.selectById(1L)).thenReturn(entity(1L, 0L, "父类目", 1, 1));
        when(categoryMapper.selectCount(any(Wrapper.class))).thenReturn(2L);

        assertThatThrownBy(() -> categoryService.delete(1L))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("子类目");
        verify(categoryMapper, never()).deleteById(any(Long.class));
        verify(redis, never()).delete(anyString());
    }

    /** 删除保护（RF4）：有商品引用 → 业务错误，不得真删。 */
    @Test
    void delete_with_product_refs_rejected() {
        when(categoryMapper.selectById(1L)).thenReturn(entity(1L, 0L, "类目", 1, 1));
        when(categoryMapper.selectCount(any(Wrapper.class))).thenReturn(0L);
        when(productMapper.selectCount(any(Wrapper.class))).thenReturn(1L);

        assertThatThrownBy(() -> categoryService.delete(1L))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("存在商品");
        verify(categoryMapper, never()).deleteById(any(Long.class));
        verify(redis, never()).delete(anyString());
    }

    /** 删除成功：逻辑删除并删缓存（RF5）。 */
    @Test
    void delete_ok_removes_and_evicts_cache() {
        when(categoryMapper.selectById(1L)).thenReturn(entity(1L, 0L, "类目", 1, 1));
        when(categoryMapper.selectCount(any(Wrapper.class))).thenReturn(0L);
        when(productMapper.selectCount(any(Wrapper.class))).thenReturn(0L);

        categoryService.delete(1L);

        verify(categoryMapper).deleteById(1L);
        verify(redis).delete("mall:category:tree");
    }

    private static CategoryEntity entity(Long id, Long parentId, String name, int sort, int status) {
        CategoryEntity entity = new CategoryEntity();
        entity.setId(id);
        entity.setParentId(parentId);
        entity.setName(name);
        entity.setSort(sort);
        entity.setStatus(status);
        return entity;
    }
}
