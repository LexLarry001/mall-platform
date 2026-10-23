package com.mall.order.review.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.mall.common.result.R;
import com.mall.order.review.dto.MerchantReviewView;
import com.mall.order.review.dto.ReviewReplyRequest;
import com.mall.order.review.service.MerchantReviewService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 商家评价管理：本店评价列表 + 一次性回复。 */
@RestController
@RequestMapping("/api/merchant/review")
public class MerchantReviewController {

    private final MerchantReviewService merchantReviewService;

    public MerchantReviewController(MerchantReviewService merchantReviewService) {
        this.merchantReviewService = merchantReviewService;
    }

    @GetMapping("/list")
    public R<Page<MerchantReviewView>> list(@RequestParam(defaultValue = "1") int pageNum,
                                            @RequestParam(defaultValue = "10") int pageSize,
                                            @RequestParam(required = false) Integer rating,
                                            @RequestParam(required = false) Boolean replied) {
        return R.ok(merchantReviewService.list(pageNum, pageSize, rating, replied));
    }

    @PostMapping("/{id}/reply")
    public R<Void> reply(@PathVariable Long id, @Valid @RequestBody ReviewReplyRequest request) {
        merchantReviewService.reply(id, request.reply());
        return R.ok();
    }
}
