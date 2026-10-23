package com.mall.order.review.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.mall.common.result.R;
import com.mall.order.review.dto.AdminReviewView;
import com.mall.order.review.dto.ReviewHideRequest;
import com.mall.order.review.service.AdminReviewService;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 平台评价审核：全平台检索、隐藏、恢复。 */
@RestController
@RequestMapping("/api/admin/review")
public class AdminReviewController {

    private final AdminReviewService adminReviewService;

    public AdminReviewController(AdminReviewService adminReviewService) {
        this.adminReviewService = adminReviewService;
    }

    @GetMapping("/list")
    @PreAuthorize("hasAuthority('review:audit:list')")
    public R<Page<AdminReviewView>> list(@RequestParam(defaultValue = "1") int pageNum,
                                         @RequestParam(defaultValue = "10") int pageSize,
                                         @RequestParam(required = false) Long shopId,
                                         @RequestParam(required = false) Integer rating,
                                         @RequestParam(required = false) Integer status,
                                         @RequestParam(required = false) String keyword) {
        return R.ok(adminReviewService.list(pageNum, pageSize, shopId, rating, status, keyword));
    }

    @PostMapping("/{id}/hide")
    @PreAuthorize("hasAuthority('review:audit:handle')")
    public R<Void> hide(@PathVariable Long id, @Valid @RequestBody ReviewHideRequest request) {
        adminReviewService.hide(id, request.reason());
        return R.ok();
    }

    @PostMapping("/{id}/restore")
    @PreAuthorize("hasAuthority('review:audit:handle')")
    public R<Void> restore(@PathVariable Long id) {
        adminReviewService.restore(id);
        return R.ok();
    }
}
