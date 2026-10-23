package com.mall.order.cart;

import com.mall.common.result.R;
import com.mall.order.cart.dto.CartAddRequest;
import com.mall.order.cart.dto.CartItemView;
import com.mall.order.cart.dto.CartUpdateRequest;
import com.mall.security.util.SecurityUtils;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 买家购物车接口（ROLE_USER，由过滤链保证）。
 *
 * <p>userId 一律取自登录态（{@link SecurityUtils#getAccountId()}），禁止前端传参。</p>
 */
@RestController
@RequestMapping("/api/user/cart")
public class CartController {

    private final CartService cartService;

    public CartController(CartService cartService) {
        this.cartService = cartService;
    }

    /** 加购（同款数量累加合并）。 */
    @PostMapping
    public R<Void> add(@Valid @RequestBody CartAddRequest request) {
        cartService.add(SecurityUtils.getAccountId(), request.skuId(), request.quantity());
        return R.ok();
    }

    /** 我的购物车列表（失效项打标 invalid，不参与结算）。 */
    @GetMapping
    public R<List<CartItemView>> list() {
        return R.ok(cartService.list(SecurityUtils.getAccountId()));
    }

    /** 修改条目数量/勾选（缺省字段保持原值）。 */
    @PutMapping("/{skuId}")
    public R<Void> update(@PathVariable Long skuId, @Valid @RequestBody CartUpdateRequest request) {
        cartService.update(SecurityUtils.getAccountId(), skuId, request.quantity(), request.checked());
        return R.ok();
    }

    /** 移除条目。 */
    @DeleteMapping("/{skuId}")
    public R<Void> remove(@PathVariable Long skuId) {
        cartService.remove(SecurityUtils.getAccountId(), skuId);
        return R.ok();
    }
}
