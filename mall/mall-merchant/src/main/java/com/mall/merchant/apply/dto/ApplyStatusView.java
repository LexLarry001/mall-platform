package com.mall.merchant.apply.dto;

import com.mall.merchant.entity.MerchantEntity;

/**
 * 商家入驻申请状态视图（商家端自查）。
 *
 * @param auditStatus 审核状态：0 待审核 / 1 已通过 / 2 已驳回
 * @param auditRemark 审核备注（驳回原因）
 * @param submitted   是否已提交过申请（subject_name 非空即视为已提交）
 */
public record ApplyStatusView(
        Integer auditStatus,
        String auditRemark,
        Boolean submitted,
        String subjectName,
        String licenseNo,
        String licenseImg,
        String shopName,
        String shopLogo,
        String shopIntro) {

    public static ApplyStatusView from(MerchantEntity entity) {
        return new ApplyStatusView(
                entity.getAuditStatus(),
                entity.getAuditRemark(),
                entity.getSubjectName() != null,
                entity.getSubjectName(),
                entity.getLicenseNo(),
                entity.getLicenseImg(),
                entity.getShopName(),
                entity.getShopLogo(),
                entity.getShopIntro());
    }
}
