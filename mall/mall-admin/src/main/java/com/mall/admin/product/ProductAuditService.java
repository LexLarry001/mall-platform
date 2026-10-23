package com.mall.admin.product;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.mall.admin.product.dto.AdminProductView;
import com.mall.common.exception.BizException;
import com.mall.common.result.ResultCode;
import com.mall.product.entity.ProductEntity;
import com.mall.product.entity.ShopEntity;
import com.mall.product.mapper.ProductMapper;
import com.mall.product.mapper.ShopMapper;
import com.mall.product.mongo.AuditLogService;
import com.mall.product.mongo.MerchantAuditLogDoc;
import com.mall.product.product.ProductCacheService;
import com.mall.product.embedding.ProductChangedEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 平台商品审核与违规下架管控（plan Task 7）。
 *
 * <p>状态机守卫：审核通过/驳回仅允许 status=1 待审核商品（重复审核直接业务错误，保证幂等）；
 * 违规下架仅允许 status=2 已上架 / 4 商家下架商品（待审核商品应走审核驳回而非管控）。</p>
 *
 * <p>一致性：任何状态变更后删除 {@code mall:product:{spuId}} 详情缓存（RF5），
 * 审核动作写 MongoDB {@code merchant_audit_log}（target_type=PRODUCT）留痕。</p>
 */
@Service
public class ProductAuditService {

    private static final int STATUS_PENDING = 1;
    private static final int STATUS_ON_SHELF = 2;
    private static final int STATUS_REJECTED = 3;
    private static final int STATUS_OFF = 4;
    private static final int STATUS_BANNED = 5;

    private final ProductMapper productMapper;
    private final ShopMapper shopMapper;
    private final ProductCacheService productCacheService;
    private final AuditLogService auditLogService;
    private final ApplicationEventPublisher eventPublisher;

    public ProductAuditService(ProductMapper productMapper, ShopMapper shopMapper,
                               ProductCacheService productCacheService, AuditLogService auditLogService,
                               ApplicationEventPublisher eventPublisher) {
        this.productMapper = productMapper;
        this.shopMapper = shopMapper;
        this.productCacheService = productCacheService;
        this.auditLogService = auditLogService;
        this.eventPublisher = eventPublisher;
    }

    /** 待审核（status=1）商品分页，店铺名联查，支持名称模糊查询。 */
    public Page<AdminProductView> listPending(String keyword, long pageNum, long pageSize) {
        Page<ProductEntity> entityPage = productMapper.selectPage(new Page<>(pageNum, pageSize),
                Wrappers.<ProductEntity>lambdaQuery()
                        .eq(ProductEntity::getStatus, STATUS_PENDING)
                        .like(StringUtils.hasText(keyword), ProductEntity::getName, keyword)
                        .orderByAsc(ProductEntity::getCreateTime));
        return toViewPage(entityPage);
    }

    /** 全平台商品分页（管控视角），支持店铺 / 状态 / 名称过滤。 */
    public Page<AdminProductView> listAll(Long shopId, Integer status, String keyword,
                                          long pageNum, long pageSize) {
        Page<ProductEntity> entityPage = productMapper.selectPage(new Page<>(pageNum, pageSize),
                Wrappers.<ProductEntity>lambdaQuery()
                        .eq(shopId != null, ProductEntity::getShopId, shopId)
                        .eq(status != null, ProductEntity::getStatus, status)
                        .like(StringUtils.hasText(keyword), ProductEntity::getName, keyword)
                        .orderByDesc(ProductEntity::getUpdateTime));
        return toViewPage(entityPage);
    }

    /**
     * 审核通过：status 1→2，清空审核备注，详情缓存失效，写 PASS 留痕。
     *
     * @throws BizException 商品不存在或非待审核状态（重复审核幂等拒绝）
     */
    @Transactional
    public void pass(Long spuId, String operator) {
        ProductEntity spu = requirePending(spuId);
        spu.setStatus(STATUS_ON_SHELF);
        spu.setAuditRemark(null);
        productMapper.updateById(spu);

        productCacheService.evict(spuId);
        auditLogService.record(MerchantAuditLogDoc.TARGET_PRODUCT, spuId,
                MerchantAuditLogDoc.ACTION_PASS, operator, null);
        eventPublisher.publishEvent(new ProductChangedEvent(spuId));
    }

    /** 审核驳回：必须填驳回原因；status 1→3，详情缓存失效，写 REJECT 留痕。 */
    @Transactional
    public void reject(Long spuId, String remark, String operator) {
        if (!StringUtils.hasText(remark)) {
            throw new BizException(ResultCode.PARAM_INVALID.getCode(), "驳回原因不能为空");
        }
        ProductEntity spu = requirePending(spuId);
        spu.setStatus(STATUS_REJECTED);
        spu.setAuditRemark(remark);
        productMapper.updateById(spu);

        productCacheService.evict(spuId);
        auditLogService.record(MerchantAuditLogDoc.TARGET_PRODUCT, spuId,
                MerchantAuditLogDoc.ACTION_REJECT, operator, remark);
        eventPublisher.publishEvent(new ProductChangedEvent(spuId));
    }

    /**
     * 违规下架：仅允许已上架（2）/ 商家下架（4）的商品，status →5，原因必填并写入审核备注；
     * 详情缓存失效，写 VIOLATION_OFF 留痕。平台下架后商家不可自行恢复（见 Task 6 状态机）。
     */
    @Transactional
    public void violationOff(Long spuId, String reason, String operator) {
        if (!StringUtils.hasText(reason)) {
            throw new BizException(ResultCode.PARAM_INVALID.getCode(), "违规原因不能为空");
        }
        ProductEntity spu = productMapper.selectById(spuId);
        if (spu == null) {
            throw new BizException("商品不存在");
        }
        if (spu.getStatus() != STATUS_ON_SHELF && spu.getStatus() != STATUS_OFF) {
            throw new BizException("仅已上架或商家下架的商品可执行违规下架");
        }
        spu.setStatus(STATUS_BANNED);
        spu.setAuditRemark(reason);
        productMapper.updateById(spu);

        productCacheService.evict(spuId);
        auditLogService.record(MerchantAuditLogDoc.TARGET_PRODUCT, spuId,
                MerchantAuditLogDoc.ACTION_VIOLATION_OFF, operator, reason);
        eventPublisher.publishEvent(new ProductChangedEvent(spuId));
    }

    /** 待审核守卫：商品必须存在且 status=1。 */
    private ProductEntity requirePending(Long spuId) {
        ProductEntity spu = productMapper.selectById(spuId);
        if (spu == null) {
            throw new BizException("商品不存在");
        }
        if (spu.getStatus() != STATUS_PENDING) {
            throw new BizException("该商品不在待审核状态");
        }
        return spu;
    }

    /** 实体分页 → 视图分页，批量联查店铺名。 */
    private Page<AdminProductView> toViewPage(Page<ProductEntity> entityPage) {
        List<ProductEntity> records = entityPage.getRecords();
        List<Long> shopIds = records.stream().map(ProductEntity::getShopId).distinct().toList();
        Map<Long, String> shopNames = shopIds.isEmpty()
                ? Map.of()
                : shopMapper.selectBatchIds(shopIds).stream()
                        .collect(Collectors.toMap(ShopEntity::getId, ShopEntity::getName));
        Page<AdminProductView> viewPage = new Page<>(entityPage.getCurrent(), entityPage.getSize(),
                entityPage.getTotal());
        viewPage.setRecords(records.stream()
                .map(spu -> AdminProductView.from(spu, shopNames.get(spu.getShopId())))
                .toList());
        return viewPage;
    }
}
