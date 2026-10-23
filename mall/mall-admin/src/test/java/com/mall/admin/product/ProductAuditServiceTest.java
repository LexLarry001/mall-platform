package com.mall.admin.product;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.mall.admin.product.dto.AdminProductView;
import com.mall.common.exception.BizException;
import com.mall.product.entity.ProductEntity;
import com.mall.product.entity.ShopEntity;
import com.mall.product.mapper.ProductMapper;
import com.mall.product.mapper.ShopMapper;
import com.mall.product.mongo.AuditLogService;
import com.mall.product.mongo.MerchantAuditLogDoc;
import com.mall.product.product.ProductCacheService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 平台商品审核与管控服务单元测试（mapper / 缓存 / 留痕均 mock）。
 *
 * <p>钉死 plan Task 7 Step 1：</p>
 * <ul>
 *   <li>① pass 非待审核商品 → 业务错误（重复审核幂等，不写库不删缓存不留痕）</li>
 *   <li>② pass 后 status=2 且 {@code mall:product:{spuId}} 缓存被删（RF5）、写 PASS 留痕</li>
 *   <li>④ violation-off 商家已下架（status=4）商品 → 允许转 5</li>
 *   <li>驳回必须填原因；违规下架必须填原因；非 2/4 状态不可违规下架</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class ProductAuditServiceTest {

    private static final Long SPU_ID = 7001L;
    private static final String OPERATOR = "ADMIN:1";

    @Mock
    private ProductMapper productMapper;
    @Mock
    private ShopMapper shopMapper;
    @Mock
    private ProductCacheService productCacheService;
    @Mock
    private AuditLogService auditLogService;
    @Mock
    private ApplicationEventPublisher eventPublisher;

    private ProductAuditService auditService;

    @BeforeEach
    void setUp() {
        auditService = new ProductAuditService(productMapper, shopMapper, productCacheService, auditLogService, eventPublisher);
    }

    @Test
    void pass_should_set_on_shelf_evict_cache_and_log() {
        when(productMapper.selectById(SPU_ID)).thenReturn(product(1));

        auditService.pass(SPU_ID, OPERATOR);

        ArgumentCaptor<ProductEntity> captor = ArgumentCaptor.forClass(ProductEntity.class);
        verify(productMapper).updateById(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(2);
        assertThat(captor.getValue().getAuditRemark()).isNull();

        verify(productCacheService).evict(SPU_ID);
        verify(auditLogService).record(eq(MerchantAuditLogDoc.TARGET_PRODUCT), eq(SPU_ID),
                eq(MerchantAuditLogDoc.ACTION_PASS), eq(OPERATOR), isNull());
    }

    @Test
    void pass_non_pending_product_should_fail_without_side_effects() {
        when(productMapper.selectById(SPU_ID)).thenReturn(product(2));

        assertThatThrownBy(() -> auditService.pass(SPU_ID, OPERATOR))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("待审核");

        verify(productMapper, never()).updateById(any(ProductEntity.class));
        verify(productCacheService, never()).evict(any());
        verify(auditLogService, never()).record(any(), any(), any(), any(), any());
    }

    @Test
    void pass_nonexistent_product_should_fail() {
        when(productMapper.selectById(SPU_ID)).thenReturn(null);

        assertThatThrownBy(() -> auditService.pass(SPU_ID, OPERATOR))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("不存在");
    }

    @Test
    void reject_should_set_status_remark_evict_and_log() {
        when(productMapper.selectById(SPU_ID)).thenReturn(product(1));

        auditService.reject(SPU_ID, "图片与实物不符", OPERATOR);

        ArgumentCaptor<ProductEntity> captor = ArgumentCaptor.forClass(ProductEntity.class);
        verify(productMapper).updateById(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(3);
        assertThat(captor.getValue().getAuditRemark()).isEqualTo("图片与实物不符");

        verify(productCacheService).evict(SPU_ID);
        verify(auditLogService).record(eq(MerchantAuditLogDoc.TARGET_PRODUCT), eq(SPU_ID),
                eq(MerchantAuditLogDoc.ACTION_REJECT), eq(OPERATOR), eq("图片与实物不符"));
    }

    @Test
    void reject_without_remark_should_fail() {
        assertThatThrownBy(() -> auditService.reject(SPU_ID, "  ", OPERATOR))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("驳回原因");

        verify(productMapper, never()).updateById(any(ProductEntity.class));
    }

    @Test
    void violation_off_on_shelf_product_should_set_banned_and_log() {
        when(productMapper.selectById(SPU_ID)).thenReturn(product(2));

        auditService.violationOff(SPU_ID, "虚假宣传", OPERATOR);

        ArgumentCaptor<ProductEntity> captor = ArgumentCaptor.forClass(ProductEntity.class);
        verify(productMapper).updateById(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(5);
        assertThat(captor.getValue().getAuditRemark()).isEqualTo("虚假宣传");

        verify(productCacheService).evict(SPU_ID);
        verify(auditLogService).record(eq(MerchantAuditLogDoc.TARGET_PRODUCT), eq(SPU_ID),
                eq(MerchantAuditLogDoc.ACTION_VIOLATION_OFF), eq(OPERATOR), eq("虚假宣传"));
    }

    /** plan Task 7 Step 1 ④：商家自行下架（status=4）的商品同样允许平台转违规下架。 */
    @Test
    void violation_off_merchant_off_product_is_allowed() {
        when(productMapper.selectById(SPU_ID)).thenReturn(product(4));

        auditService.violationOff(SPU_ID, "历史违规追罚", OPERATOR);

        ArgumentCaptor<ProductEntity> captor = ArgumentCaptor.forClass(ProductEntity.class);
        verify(productMapper).updateById(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(5);
    }

    @Test
    void violation_off_pending_product_should_fail() {
        when(productMapper.selectById(SPU_ID)).thenReturn(product(1));

        assertThatThrownBy(() -> auditService.violationOff(SPU_ID, "原因", OPERATOR))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("已上架");

        verify(productMapper, never()).updateById(any(ProductEntity.class));
    }

    @Test
    void violation_off_banned_product_should_fail() {
        when(productMapper.selectById(SPU_ID)).thenReturn(product(5));

        assertThatThrownBy(() -> auditService.violationOff(SPU_ID, "原因", OPERATOR))
                .isInstanceOf(BizException.class);

        verify(productMapper, never()).updateById(any(ProductEntity.class));
    }

    @Test
    void violation_off_without_reason_should_fail() {
        assertThatThrownBy(() -> auditService.violationOff(SPU_ID, null, OPERATOR))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("原因");

        verify(productMapper, never()).updateById(any(ProductEntity.class));
    }

    @Test
    void list_pending_should_join_shop_name() {
        ProductEntity spu = product(1);
        Page<ProductEntity> entityPage = new Page<>(1, 10);
        entityPage.setRecords(List.of(spu));
        entityPage.setTotal(1);
        when(productMapper.selectPage(any(), any())).thenReturn(entityPage);
        when(shopMapper.selectBatchIds(anyCollection())).thenReturn(List.of(shop(3001L, "流转杂货铺")));

        Page<AdminProductView> result = auditService.listPending(null, 1, 10);

        assertThat(result.getTotal()).isEqualTo(1);
        AdminProductView view = result.getRecords().get(0);
        assertThat(view.id()).isEqualTo(SPU_ID);
        assertThat(view.name()).isEqualTo("流转测试商品");
        assertThat(view.shopName()).isEqualTo("流转杂货铺");
        assertThat(view.status()).isEqualTo(1);
    }

    @Test
    void list_all_should_map_view_with_shop_name() {
        ProductEntity spu = product(2);
        Page<ProductEntity> entityPage = new Page<>(1, 10);
        entityPage.setRecords(List.of(spu));
        entityPage.setTotal(1);
        when(productMapper.selectPage(any(), any())).thenReturn(entityPage);
        when(shopMapper.selectBatchIds(anyCollection())).thenReturn(List.of(shop(3001L, "流转杂货铺")));

        Page<AdminProductView> result = auditService.listAll(3001L, 2, "流转", 1, 10);

        assertThat(result.getRecords()).hasSize(1);
        assertThat(result.getRecords().get(0).shopName()).isEqualTo("流转杂货铺");
    }

    private static ProductEntity product(int status) {
        ProductEntity entity = new ProductEntity();
        entity.setId(SPU_ID);
        entity.setShopId(3001L);
        entity.setCategoryId(101L);
        entity.setName("流转测试商品");
        entity.setMainImg("/static/202610/a.png");
        entity.setPrice(new BigDecimal("99.00"));
        entity.setSales(0);
        entity.setStatus(status);
        return entity;
    }

    private static ShopEntity shop(Long id, String name) {
        ShopEntity shop = new ShopEntity();
        shop.setId(id);
        shop.setName(name);
        return shop;
    }
}
