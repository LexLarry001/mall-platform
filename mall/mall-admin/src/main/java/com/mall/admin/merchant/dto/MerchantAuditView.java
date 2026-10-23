package com.mall.admin.merchant.dto;

import com.mall.merchant.entity.MerchantEntity;

import java.time.LocalDateTime;

/**
 * 平台入驻审核列表视图（不含密码等敏感字段）。
 */
public record MerchantAuditView(
        Long id,
        String username,
        String contactPhone,
        Integer auditStatus,
        String auditRemark,
        Integer status,
        String subjectName,
        String licenseNo,
        String licenseImg,
        String shopName,
        String shopLogo,
        String shopIntro,
        LocalDateTime createTime,
        LocalDateTime updateTime) {

    public static MerchantAuditView from(MerchantEntity entity) {
        return new MerchantAuditView(
                entity.getId(),
                entity.getUsername(),
                entity.getContactPhone(),
                entity.getAuditStatus(),
                entity.getAuditRemark(),
                entity.getStatus(),
                entity.getSubjectName(),
                entity.getLicenseNo(),
                entity.getLicenseImg(),
                entity.getShopName(),
                entity.getShopLogo(),
                entity.getShopIntro(),
                entity.getCreateTime(),
                entity.getUpdateTime());
    }
}
