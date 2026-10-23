package com.mall.admin.merchant;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.mall.admin.merchant.dto.MerchantAuditView;
import com.mall.common.exception.BizException;
import com.mall.merchant.entity.MerchantEntity;
import com.mall.merchant.mapper.MerchantMapper;
import com.mall.product.entity.ShopEntity;
import com.mall.product.mapper.ShopMapper;
import com.mall.product.mongo.AuditLogService;
import com.mall.product.mongo.MerchantAuditLogDoc;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 平台入驻审核服务单元测试（mapper 与审核留痕均为 mock）。
 *
 * <p>钉死 plan Task 3 Step 1 的③与 RF7（审核幂等与事务）：</p>
 * <ul>
 *   <li>通过 → merchant.audit_status=1 且按申请草稿建 shop 行，写 PASS 留痕</li>
 *   <li>重复"通过"同一申请 → 第二次业务错误且不再插 shop（merchant_id 唯一兜底）</li>
 *   <li>并发下唯一键冲突 → 转为业务错误而非 500</li>
 *   <li>驳回必须填原因；非待审核申请不可审</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class MerchantAuditServiceTest {

    private static final Long MERCHANT_ID = 1001L;

    @Mock
    private MerchantMapper merchantMapper;
    @Mock
    private ShopMapper shopMapper;
    @Mock
    private AuditLogService auditLogService;

    private MerchantAuditService auditService;

    @BeforeEach
    void setUp() {
        auditService = new MerchantAuditService(merchantMapper, shopMapper, auditLogService);
    }

    @Test
    void pass_should_approve_merchant_and_create_shop_from_draft() {
        when(merchantMapper.selectById(MERCHANT_ID)).thenReturn(pendingMerchant());

        auditService.pass(MERCHANT_ID, "ADMIN:1");

        ArgumentCaptor<MerchantEntity> merchantCaptor = ArgumentCaptor.forClass(MerchantEntity.class);
        verify(merchantMapper).updateById(merchantCaptor.capture());
        assertThat(merchantCaptor.getValue().getAuditStatus()).isEqualTo(1);
        assertThat(merchantCaptor.getValue().getAuditRemark()).isNull();

        ArgumentCaptor<ShopEntity> shopCaptor = ArgumentCaptor.forClass(ShopEntity.class);
        verify(shopMapper).insert(shopCaptor.capture());
        ShopEntity shop = shopCaptor.getValue();
        assertThat(shop.getMerchantId()).isEqualTo(MERCHANT_ID);
        assertThat(shop.getName()).isEqualTo("流转测试店");
        assertThat(shop.getLogo()).isEqualTo("/static/202610/logo.png");
        assertThat(shop.getIntro()).isEqualTo("测试简介");
        assertThat(shop.getBizStatus()).isEqualTo(1);

        verify(auditLogService).record(eq(MerchantAuditLogDoc.TARGET_MERCHANT_APPLY), eq(MERCHANT_ID),
                eq(MerchantAuditLogDoc.ACTION_PASS), eq("ADMIN:1"), org.mockito.ArgumentMatchers.isNull());
    }

    @Test
    void pass_twice_should_fail_and_never_insert_shop_again() {
        MerchantEntity merchant = pendingMerchant();
        when(merchantMapper.selectById(MERCHANT_ID)).thenReturn(merchant);

        auditService.pass(MERCHANT_ID, "ADMIN:1");
        // 第一次通过后服务内已把同一实体的 auditStatus 置 1，第二次审核应立即拒绝
        assertThatThrownBy(() -> auditService.pass(MERCHANT_ID, "ADMIN:1"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("待审核");

        verify(shopMapper, times(1)).insert(any(ShopEntity.class));
    }

    @Test
    void pass_should_convert_duplicate_shop_key_into_biz_error() {
        when(merchantMapper.selectById(MERCHANT_ID)).thenReturn(pendingMerchant());
        when(shopMapper.insert(any(ShopEntity.class)))
                .thenThrow(new DuplicateKeyException("Duplicate entry for key 'uk_shop_merchant'"));

        assertThatThrownBy(() -> auditService.pass(MERCHANT_ID, "ADMIN:1"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("店铺");
    }

    @Test
    void pass_for_unsubmitted_merchant_should_fail() {
        MerchantEntity merchant = new MerchantEntity();
        merchant.setId(MERCHANT_ID);
        merchant.setAuditStatus(0);
        merchant.setSubjectName(null);
        when(merchantMapper.selectById(MERCHANT_ID)).thenReturn(merchant);

        assertThatThrownBy(() -> auditService.pass(MERCHANT_ID, "ADMIN:1"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("待审核");

        verify(shopMapper, never()).insert(any(ShopEntity.class));
    }

    @Test
    void reject_should_set_remark_and_log() {
        when(merchantMapper.selectById(MERCHANT_ID)).thenReturn(pendingMerchant());

        auditService.reject(MERCHANT_ID, "资料不全", "ADMIN:1");

        ArgumentCaptor<MerchantEntity> captor = ArgumentCaptor.forClass(MerchantEntity.class);
        verify(merchantMapper).updateById(captor.capture());
        assertThat(captor.getValue().getAuditStatus()).isEqualTo(2);
        assertThat(captor.getValue().getAuditRemark()).isEqualTo("资料不全");

        verify(auditLogService).record(eq(MerchantAuditLogDoc.TARGET_MERCHANT_APPLY), eq(MERCHANT_ID),
                eq(MerchantAuditLogDoc.ACTION_REJECT), eq("ADMIN:1"), eq("资料不全"));
    }

    @Test
    void reject_without_remark_should_fail() {
        assertThatThrownBy(() -> auditService.reject(MERCHANT_ID, " ", "ADMIN:1"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("驳回原因");

        verify(merchantMapper, never()).updateById(any(MerchantEntity.class));
    }

    @Test
    void list_submitted_should_map_to_view_without_password() {
        Page<MerchantEntity> entityPage = new Page<>(1, 10);
        entityPage.setRecords(List.of(pendingMerchant()));
        entityPage.setTotal(1);
        when(merchantMapper.selectPage(any(), any())).thenReturn(entityPage);

        Page<MerchantAuditView> result = auditService.listSubmitted("流转", 1, 10);

        assertThat(result.getTotal()).isEqualTo(1);
        MerchantAuditView view = result.getRecords().get(0);
        assertThat(view.username()).isEqualTo("seller");
        assertThat(view.subjectName()).isEqualTo("流转测试商贸有限公司");
        assertThat(view.shopName()).isEqualTo("流转测试店");
        assertThat(view.auditStatus()).isEqualTo(0);
    }

    @Test
    void change_status_should_update_merchant_status() {
        MerchantEntity merchant = pendingMerchant();
        merchant.setStatus(1);
        when(merchantMapper.selectById(MERCHANT_ID)).thenReturn(merchant);

        auditService.changeStatus(MERCHANT_ID, 0);

        ArgumentCaptor<MerchantEntity> captor = ArgumentCaptor.forClass(MerchantEntity.class);
        verify(merchantMapper).updateById(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(0);
    }

    @Test
    void change_status_with_illegal_value_should_fail() {
        assertThatThrownBy(() -> auditService.changeStatus(MERCHANT_ID, 7))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("状态");

        verify(merchantMapper, never()).updateById(any(MerchantEntity.class));
    }

    private static MerchantEntity pendingMerchant() {
        MerchantEntity entity = new MerchantEntity();
        entity.setId(MERCHANT_ID);
        entity.setUsername("seller");
        entity.setPassword("hashed");
        entity.setContactPhone("13800000000");
        entity.setAuditStatus(0);
        entity.setSubjectName("流转测试商贸有限公司");
        entity.setLicenseNo("91330100FLOW0001");
        entity.setLicenseImg("/static/202610/license.png");
        entity.setShopName("流转测试店");
        entity.setShopLogo("/static/202610/logo.png");
        entity.setShopIntro("测试简介");
        entity.setStatus(1);
        return entity;
    }
}
