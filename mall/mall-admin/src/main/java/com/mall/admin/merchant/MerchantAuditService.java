package com.mall.admin.merchant;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.mall.admin.merchant.dto.MerchantAuditView;
import com.mall.common.exception.BizException;
import com.mall.common.result.ResultCode;
import com.mall.merchant.entity.MerchantEntity;
import com.mall.merchant.mapper.MerchantMapper;
import com.mall.product.entity.ShopEntity;
import com.mall.product.mapper.ShopMapper;
import com.mall.product.mongo.AuditLogService;
import com.mall.product.mongo.MerchantAuditLogDoc;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * 平台入驻审核与商家管控。
 *
 * <p>审核通过动作把 merchant 状态更新与 shop 建行放在同一事务（plan Review Focus 7）；
 * {@code shop.merchant_id} 唯一约束兜底并发下的重复通过。审核留痕写 MongoDB。</p>
 */
@Service
public class MerchantAuditService {

    private static final int AUDIT_STATUS_PENDING = 0;
    private static final int AUDIT_STATUS_APPROVED = 1;
    private static final int AUDIT_STATUS_REJECTED = 2;

    private final MerchantMapper merchantMapper;
    private final ShopMapper shopMapper;
    private final AuditLogService auditLogService;

    public MerchantAuditService(MerchantMapper merchantMapper, ShopMapper shopMapper,
                                AuditLogService auditLogService) {
        this.merchantMapper = merchantMapper;
        this.shopMapper = shopMapper;
        this.auditLogService = auditLogService;
    }

    /** 已提交申请的商家分页（subject_name 非空），支持按用户名/主体名模糊查询。 */
    public Page<MerchantAuditView> listSubmitted(String keyword, long pageNum, long pageSize) {
        Page<MerchantEntity> entityPage = merchantMapper.selectPage(new Page<>(pageNum, pageSize),
                Wrappers.<MerchantEntity>lambdaQuery()
                        .isNotNull(MerchantEntity::getSubjectName)
                        .and(StringUtils.hasText(keyword), wrapper -> wrapper
                                .like(MerchantEntity::getUsername, keyword)
                                .or()
                                .like(MerchantEntity::getSubjectName, keyword))
                        .orderByDesc(MerchantEntity::getUpdateTime));
        Page<MerchantAuditView> viewPage = new Page<>(entityPage.getCurrent(), entityPage.getSize(),
                entityPage.getTotal());
        viewPage.setRecords(entityPage.getRecords().stream().map(MerchantAuditView::from).toList());
        return viewPage;
    }

    /**
     * 审核通过：merchant.audit_status=1 并按申请草稿建 shop 行（同事务）。
     *
     * @throws BizException 非待审核申请；并发下重复开店（唯一键兜底）
     */
    @Transactional
    public void pass(Long merchantId, String operator) {
        MerchantEntity merchant = requirePending(merchantId);
        merchant.setAuditStatus(AUDIT_STATUS_APPROVED);
        merchant.setAuditRemark(null);
        merchantMapper.updateById(merchant);

        ShopEntity shop = new ShopEntity();
        shop.setMerchantId(merchantId);
        shop.setName(merchant.getShopName());
        shop.setLogo(merchant.getShopLogo());
        shop.setIntro(merchant.getShopIntro());
        shop.setBizStatus(1);
        try {
            shopMapper.insert(shop);
        } catch (DuplicateKeyException ex) {
            // 并发审核下的兜底：shop.merchant_id 唯一，重复通过转业务错误并回滚事务
            throw new BizException("该商家已存在店铺，请勿重复通过");
        }

        auditLogService.record(MerchantAuditLogDoc.TARGET_MERCHANT_APPLY, merchantId,
                MerchantAuditLogDoc.ACTION_PASS, operator, null);
    }

    /** 审核驳回：必须填驳回原因。 */
    @Transactional
    public void reject(Long merchantId, String remark, String operator) {
        if (!StringUtils.hasText(remark)) {
            throw new BizException(ResultCode.PARAM_INVALID.getCode(), "驳回原因不能为空");
        }
        MerchantEntity merchant = requirePending(merchantId);
        merchant.setAuditStatus(AUDIT_STATUS_REJECTED);
        merchant.setAuditRemark(remark);
        merchantMapper.updateById(merchant);

        auditLogService.record(MerchantAuditLogDoc.TARGET_MERCHANT_APPLY, merchantId,
                MerchantAuditLogDoc.ACTION_REJECT, operator, remark);
    }

    /** 冻结（0）/ 启用（1）商家账号；冻结后商家无法登录。 */
    public void changeStatus(Long merchantId, Integer status) {
        if (status == null || (status != 0 && status != 1)) {
            throw new BizException(ResultCode.PARAM_INVALID.getCode(), "非法状态值");
        }
        MerchantEntity merchant = merchantMapper.selectById(merchantId);
        if (merchant == null) {
            throw new BizException("商家账号不存在");
        }
        merchant.setStatus(status);
        merchantMapper.updateById(merchant);
    }

    /** 待审核 = audit_status 0 且已提交申请（subject_name 非空）。 */
    private MerchantEntity requirePending(Long merchantId) {
        MerchantEntity merchant = merchantMapper.selectById(merchantId);
        if (merchant == null) {
            throw new BizException("商家账号不存在");
        }
        if (merchant.getAuditStatus() != AUDIT_STATUS_PENDING || merchant.getSubjectName() == null) {
            throw new BizException("该申请不在待审核状态");
        }
        return merchant;
    }
}
