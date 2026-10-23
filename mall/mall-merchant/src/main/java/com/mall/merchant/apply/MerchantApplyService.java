package com.mall.merchant.apply;

import com.mall.common.exception.BizException;
import com.mall.merchant.apply.dto.ApplyRequest;
import com.mall.merchant.apply.dto.ApplyStatusView;
import com.mall.merchant.entity.MerchantEntity;
import com.mall.merchant.mapper.MerchantMapper;
import com.mall.product.mongo.AuditLogService;
import com.mall.product.mongo.MerchantAuditLogDoc;
import org.springframework.stereotype.Service;

/**
 * 商家入驻申请。
 *
 * <p>申请信息存 merchant 草稿字段；"是否已提交"以 {@code subject_name IS NOT NULL} 判断。
 * 可提交的状态仅两种：从未提交（audit_status=0 且无草稿）、被驳回（audit_status=2，
 * 重填后覆盖草稿并回到待审核）。审核留痕写 MongoDB（SUBMIT）。</p>
 */
@Service
public class MerchantApplyService {

    private static final int AUDIT_STATUS_PENDING = 0;
    private static final int AUDIT_STATUS_APPROVED = 1;

    private final MerchantMapper merchantMapper;
    private final AuditLogService auditLogService;

    public MerchantApplyService(MerchantMapper merchantMapper, AuditLogService auditLogService) {
        this.merchantMapper = merchantMapper;
        this.auditLogService = auditLogService;
    }

    /**
     * 提交入驻申请。
     *
     * @throws BizException 待审核中重复提交 / 已通过再提交
     */
    public void submit(Long merchantId, ApplyRequest request) {
        MerchantEntity merchant = requireMerchant(merchantId);
        if (merchant.getAuditStatus() == AUDIT_STATUS_APPROVED) {
            throw new BizException("入驻审核已通过，无需重复申请");
        }
        boolean alreadySubmitted = merchant.getSubjectName() != null;
        if (merchant.getAuditStatus() == AUDIT_STATUS_PENDING && alreadySubmitted) {
            throw new BizException("入驻申请审核中，请勿重复提交");
        }

        merchant.setSubjectName(request.subjectName());
        merchant.setLicenseNo(request.licenseNo());
        merchant.setLicenseImg(request.licenseImg());
        merchant.setShopName(request.shopName());
        merchant.setShopLogo(request.shopLogo());
        merchant.setShopIntro(request.shopIntro());
        merchant.setAuditStatus(AUDIT_STATUS_PENDING);
        merchant.setAuditRemark(null);
        merchantMapper.updateById(merchant);

        auditLogService.record(MerchantAuditLogDoc.TARGET_MERCHANT_APPLY, merchantId,
                MerchantAuditLogDoc.ACTION_SUBMIT, "MERCHANT:" + merchantId, null);
    }

    /** 查询当前商家的申请状态与草稿。 */
    public ApplyStatusView status(Long merchantId) {
        return ApplyStatusView.from(requireMerchant(merchantId));
    }

    private MerchantEntity requireMerchant(Long merchantId) {
        MerchantEntity merchant = merchantMapper.selectById(merchantId);
        if (merchant == null) {
            throw new BizException("商家账号不存在");
        }
        return merchant;
    }
}
