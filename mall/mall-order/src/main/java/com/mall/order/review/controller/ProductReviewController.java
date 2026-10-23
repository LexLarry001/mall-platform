package com.mall.order.review.controller;

import com.mall.common.result.R;
import com.mall.order.review.dto.ReviewPageView;
import com.mall.order.review.dto.ReviewSummaryView;
import com.mall.order.review.service.ReviewAiSummaryService;
import com.mall.order.review.service.ReviewQueryService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 商品评价公开接口：评价列表与 AI 评价摘要。 */
@RestController
@RequestMapping("/api/product")
public class ProductReviewController {

    private final ReviewQueryService queryService;
    private final ReviewAiSummaryService summaryService;

    public ProductReviewController(ReviewQueryService queryService,
                                   ReviewAiSummaryService summaryService) {
        this.queryService = queryService;
        this.summaryService = summaryService;
    }

    /** 公开评价分页。 */
    @GetMapping("/{spuId}/reviews")
    public R<ReviewPageView> reviews(@PathVariable Long spuId,
                                     @RequestParam(defaultValue = "1") int pageNum,
                                     @RequestParam(defaultValue = "10") int pageSize,
                                     @RequestParam(required = false) Integer rating,
                                     @RequestParam(required = false) Boolean hasImage,
                                     @RequestParam(required = false) String tag) {
        return R.ok(queryService.publicPage(spuId, pageNum, pageSize, rating, hasImage, tag));
    }

    /** AI 评价摘要（评价不足或 AI 不可用时自动返回统计版）。 */
    @GetMapping("/{spuId}/review-summary")
    public R<ReviewSummaryView> summary(@PathVariable Long spuId) {
        return R.ok(summaryService.summary(spuId));
    }
}
