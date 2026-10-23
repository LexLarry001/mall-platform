package com.mall.merchant.apply;

import com.mall.common.exception.BizException;
import com.mall.merchant.apply.dto.ApplyRequest;
import com.mall.merchant.apply.dto.ApplyStatusView;
import com.mall.merchant.entity.MerchantEntity;
import com.mall.merchant.mapper.MerchantMapper;
import com.mall.product.mongo.AuditLogService;
import com.mall.product.mongo.MerchantAuditLogDoc;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 商家入驻申请服务单元测试（mapper 与审核留痕均为 mock）。
 *
 * <p>钉死 plan Task 3 Step 1 的①②与提交语义：</p>
 * <ul>
 *   <li>首次提交 → 草稿字段落 merchant、audit_status 归 0、写 SUBMIT 留痕</li>
 *   <li>待审核中重复提交 → 业务错误（①）</li>
 *   <li>已通过再提交 → 业务错误</li>
 *   <li>驳回后可重填再提交，状态归 0（②）</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class MerchantApplyServiceTest {

    private static final Long MERCHANT_ID = 1001L;

    @Mock
    private MerchantMapper merchantMapper;
    @Mock
    private AuditLogService auditLogService;

    private MerchantApplyService applyService;

    @BeforeEach
    void setUp() {
        applyService = new MerchantApplyService(merchantMapper, auditLogService);
    }

    @Test
    void first_submit_should_save_draft_reset_audit_status_and_log() {
        when(merchantMapper.selectById(MERCHANT_ID)).thenReturn(merchant(0, null));

        applyService.submit(MERCHANT_ID, request());

        ArgumentCaptor<MerchantEntity> captor = ArgumentCaptor.forClass(MerchantEntity.class);
        verify(merchantMapper).updateById(captor.capture());
        MerchantEntity saved = captor.getValue();
        assertThat(saved.getSubjectName()).isEqualTo("流转测试商贸有限公司");
        assertThat(saved.getLicenseNo()).isEqualTo("91330100FLOW0001");
        assertThat(saved.getShopName()).isEqualTo("流转测试店");
        assertThat(saved.getAuditStatus()).isEqualTo(0);
        assertThat(saved.getAuditRemark()).isNull();

        verify(auditLogService).record(eq(MerchantAuditLogDoc.TARGET_MERCHANT_APPLY), eq(MERCHANT_ID),
                eq(MerchantAuditLogDoc.ACTION_SUBMIT), any(String.class), isNull());
    }

    @Test
    void submit_while_pending_should_fail() {
        // 待审核中 = audit_status 0 且已填过申请（subject_name 非空）
        when(merchantMapper.selectById(MERCHANT_ID)).thenReturn(merchant(0, "已提交过的主体"));

        assertThatThrownBy(() -> applyService.submit(MERCHANT_ID, request()))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("重复提交");

        verify(merchantMapper, never()).updateById(any(MerchantEntity.class));
    }

    @Test
    void submit_when_already_approved_should_fail() {
        when(merchantMapper.selectById(MERCHANT_ID)).thenReturn(merchant(1, "已通过的主体"));

        assertThatThrownBy(() -> applyService.submit(MERCHANT_ID, request()))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("已通过");

        verify(merchantMapper, never()).updateById(any(MerchantEntity.class));
    }

    @Test
    void resubmit_after_reject_should_overwrite_draft_and_reset_status() {
        when(merchantMapper.selectById(MERCHANT_ID)).thenReturn(merchant(2, "旧主体"));

        applyService.submit(MERCHANT_ID, request());

        ArgumentCaptor<MerchantEntity> captor = ArgumentCaptor.forClass(MerchantEntity.class);
        verify(merchantMapper).updateById(captor.capture());
        assertThat(captor.getValue().getSubjectName()).isEqualTo("流转测试商贸有限公司");
        assertThat(captor.getValue().getAuditStatus()).isEqualTo(0);
        assertThat(captor.getValue().getAuditRemark()).isNull();
    }

    @Test
    void status_should_return_current_draft_fields() {
        MerchantEntity entity = merchant(2, "旧主体");
        entity.setAuditRemark("资料不全");
        when(merchantMapper.selectById(MERCHANT_ID)).thenReturn(entity);

        ApplyStatusView view = applyService.status(MERCHANT_ID);

        assertThat(view.auditStatus()).isEqualTo(2);
        assertThat(view.auditRemark()).isEqualTo("资料不全");
        assertThat(view.subjectName()).isEqualTo("旧主体");
    }

    private static ApplyRequest request() {
        return new ApplyRequest("流转测试商贸有限公司", "91330100FLOW0001", "/static/202610/license.png",
                "流转测试店", "/static/202610/logo.png", "测试简介");
    }

    private static MerchantEntity merchant(int auditStatus, String subjectName) {
        MerchantEntity entity = new MerchantEntity();
        entity.setId(MERCHANT_ID);
        entity.setUsername("seller");
        entity.setAuditStatus(auditStatus);
        entity.setSubjectName(subjectName);
        entity.setStatus(1);
        return entity;
    }
}
