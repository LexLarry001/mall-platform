package com.mall.merchant.apply;

import com.mall.common.result.R;
import com.mall.merchant.apply.dto.ApplyRequest;
import com.mall.merchant.apply.dto.ApplyStatusView;
import com.mall.security.util.SecurityUtils;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 商家入驻申请接口（ROLE_MERCHANT，由过滤链保证）。
 *
 * <p>merchantId 一律取自登录态（{@link SecurityUtils}），禁止前端传参。</p>
 */
@RestController
@RequestMapping("/api/merchant/apply")
public class MerchantApplyController {

    private final MerchantApplyService applyService;

    public MerchantApplyController(MerchantApplyService applyService) {
        this.applyService = applyService;
    }

    /** 提交入驻申请（驳回后可重填再提交）。 */
    @PostMapping
    public R<Void> submit(@Valid @RequestBody ApplyRequest request) {
        applyService.submit(SecurityUtils.getAccountId(), request);
        return R.ok();
    }

    /** 查询当前商家的申请状态与草稿。 */
    @GetMapping("/status")
    public R<ApplyStatusView> status() {
        return R.ok(applyService.status(SecurityUtils.getAccountId()));
    }
}
