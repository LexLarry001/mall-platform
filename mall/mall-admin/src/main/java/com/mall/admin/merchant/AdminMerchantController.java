package com.mall.admin.merchant;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.mall.admin.merchant.dto.MerchantAuditView;
import com.mall.common.result.R;
import com.mall.security.util.SecurityUtils;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 平台商家管理接口：入驻审核 + 冻结/启用。
 *
 * <p>端点除过滤链的 {@code ROLE_ADMIN} 约束外，按权限码做方法级鉴权；
 * 操作者标识取自登录态。</p>
 */
@RestController
@RequestMapping("/api/admin/merchant")
public class AdminMerchantController {

    private final MerchantAuditService merchantAuditService;

    public AdminMerchantController(MerchantAuditService merchantAuditService) {
        this.merchantAuditService = merchantAuditService;
    }

    /** 入驻审核列表（仅已提交申请的商家）。 */
    @GetMapping("/audit/list")
    @PreAuthorize("hasAuthority('merchant:audit:list')")
    public R<Page<MerchantAuditView>> auditList(@RequestParam(required = false) String keyword,
                                                @RequestParam(defaultValue = "1") long pageNum,
                                                @RequestParam(defaultValue = "10") long pageSize) {
        return R.ok(merchantAuditService.listSubmitted(keyword, pageNum, pageSize));
    }

    /** 审核通过：自动按申请草稿建店。 */
    @PostMapping("/audit/{merchantId}/pass")
    @PreAuthorize("hasAuthority('merchant:audit:handle')")
    public R<Void> pass(@PathVariable Long merchantId) {
        merchantAuditService.pass(merchantId, operator());
        return R.ok();
    }

    /** 审核驳回：必须填驳回原因。 */
    @PostMapping("/audit/{merchantId}/reject")
    @PreAuthorize("hasAuthority('merchant:audit:handle')")
    public R<Void> reject(@PathVariable Long merchantId, @RequestParam(required = false) String remark) {
        merchantAuditService.reject(merchantId, remark, operator());
        return R.ok();
    }

    /** 冻结（status=0）/ 启用（status=1）商家账号。 */
    @PostMapping("/{merchantId}/status")
    @PreAuthorize("hasAuthority('merchant:manage:status')")
    public R<Void> changeStatus(@PathVariable Long merchantId, @RequestParam Integer status) {
        merchantAuditService.changeStatus(merchantId, status);
        return R.ok();
    }

    private static String operator() {
        return "ADMIN:" + SecurityUtils.getAccountId();
    }
}
