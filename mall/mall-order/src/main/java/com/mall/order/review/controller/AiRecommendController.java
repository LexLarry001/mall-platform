package com.mall.order.review.controller;

import com.mall.common.result.R;
import com.mall.order.review.dto.RecommendRequest;
import com.mall.order.review.dto.RecommendView;
import com.mall.order.review.service.ReviewRecommendService;
import com.mall.security.util.SecurityUtils;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** AI 客服/导购：按真实评价推荐商品。 */
@RestController
@RequestMapping("/api/user/ai")
public class AiRecommendController {

    private final ReviewRecommendService recommendService;

    public AiRecommendController(ReviewRecommendService recommendService) {
        this.recommendService = recommendService;
    }

    @PostMapping("/recommend")
    public R<RecommendView> recommend(@Valid @RequestBody RecommendRequest request) {
        return R.ok(recommendService.recommend(SecurityUtils.getAccountId(), request));
    }
}
