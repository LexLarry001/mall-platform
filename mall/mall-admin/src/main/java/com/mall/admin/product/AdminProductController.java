package com.mall.admin.product;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.mall.admin.product.dto.AdminProductView;
import com.mall.ai.gateway.AiService;
import com.mall.common.exception.BizException;
import com.mall.common.result.R;
import com.mall.product.embedding.ProductEmbeddingService;
import com.mall.security.util.SecurityUtils;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 平台商品管理接口：商品审核（通过/驳回）+ 全平台商品管控（查询/违规下架）。
 *
 * <p>端点除过滤链的 {@code ROLE_ADMIN} 约束外，按权限码做方法级鉴权；
 * 操作者标识取自登录态。</p>
 */
@RestController
@RequestMapping("/api/admin/product")
public class AdminProductController {

    private final ProductAuditService productAuditService;
    private final ProductEmbeddingService productEmbeddingService;
    private final AiService aiService;

    public AdminProductController(ProductAuditService productAuditService,
                                  ProductEmbeddingService productEmbeddingService,
                                  AiService aiService) {
        this.productAuditService = productAuditService;
        this.productEmbeddingService = productEmbeddingService;
        this.aiService = aiService;
    }

    /** 待审核商品列表（status=1，含店铺名）。 */
    @GetMapping("/audit/list")
    @PreAuthorize("hasAuthority('product:audit:list')")
    public R<Page<AdminProductView>> auditList(@RequestParam(required = false) String keyword,
                                               @RequestParam(defaultValue = "1") long pageNum,
                                               @RequestParam(defaultValue = "10") long pageSize) {
        return R.ok(productAuditService.listPending(keyword, pageNum, pageSize));
    }

    /** 审核通过：商品上架（status 1→2）。 */
    @PostMapping("/{spuId}/audit/pass")
    @PreAuthorize("hasAuthority('product:audit:handle')")
    public R<Void> pass(@PathVariable Long spuId) {
        productAuditService.pass(spuId, operator());
        return R.ok();
    }

    /** 审核驳回：必须填驳回原因（status 1→3）。 */
    @PostMapping("/{spuId}/audit/reject")
    @PreAuthorize("hasAuthority('product:audit:handle')")
    public R<Void> reject(@PathVariable Long spuId, @RequestParam(required = false) String remark) {
        productAuditService.reject(spuId, remark, operator());
        return R.ok();
    }

    /** 全平台商品管控列表，支持店铺 / 状态 / 名称过滤。 */
    @GetMapping("/list")
    @PreAuthorize("hasAuthority('product:manage:list')")
    public R<Page<AdminProductView>> manageList(@RequestParam(required = false) Long shopId,
                                                @RequestParam(required = false) Integer status,
                                                @RequestParam(required = false) String keyword,
                                                @RequestParam(defaultValue = "1") long pageNum,
                                                @RequestParam(defaultValue = "10") long pageSize) {
        return R.ok(productAuditService.listAll(shopId, status, keyword, pageNum, pageSize));
    }

    /** 违规下架：已上架/商家下架商品 → 平台下架（status →5），原因必填。 */
    @PostMapping("/{spuId}/violation-off")
    @PreAuthorize("hasAuthority('product:manage:off')")
    public R<Void> violationOff(@PathVariable Long spuId, @RequestParam(required = false) String reason) {
        productAuditService.violationOff(spuId, reason, operator());
        return R.ok();
    }

    /** 全量重建商品语义向量（后台异步执行；未启用 Embedding 时拒绝）。 */
    @PostMapping("/embedding/rebuild")
    @PreAuthorize("hasAuthority('product:manage:list')")
    public R<Void> rebuildEmbedding() {
        if (!aiService.isEmbeddingEnabled()) {
            throw new BizException("语义向量服务未启用，请先配置 MALL_EMBED_API_KEY 并重启后端");
        }
        productEmbeddingService.rebuildAllAsync();
        return R.ok();
    }

    private static String operator() {
        return "ADMIN:" + SecurityUtils.getAccountId();
    }
}
