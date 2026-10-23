package com.mall.order.review.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.mall.common.result.R;
import com.mall.order.review.dto.MyReviewView;
import com.mall.order.review.dto.ReviewCreateRequest;
import com.mall.order.review.service.ReviewQueryService;
import com.mall.order.review.service.ReviewService;
import com.mall.security.util.SecurityUtils;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 买家评价接口（M10）：提交评价、我的评价。 */
@RestController
@RequestMapping("/api/user/review")
public class BuyerReviewController {

    private final ReviewService reviewService;
    private final ReviewQueryService queryService;

    public BuyerReviewController(ReviewService reviewService, ReviewQueryService queryService) {
        this.reviewService = reviewService;
        this.queryService = queryService;
    }

    /** 对已完成订单项发表评价。 */
    @PostMapping
    public R<Long> create(@Valid @RequestBody ReviewCreateRequest request) {
        return R.ok(reviewService.create(SecurityUtils.getAccountId(), request));
    }

    /** 我的评价分页。 */
    @GetMapping("/my")
    public R<Page<MyReviewView>> my(@RequestParam(defaultValue = "1") int pageNum,
                                    @RequestParam(defaultValue = "10") int pageSize) {
        return R.ok(queryService.myPage(SecurityUtils.getAccountId(), pageNum, pageSize));
    }
}
