package com.mall.security.util;

import com.mall.common.exception.BizException;
import com.mall.common.result.ResultCode;
import com.mall.security.token.AccountType;
import com.mall.security.token.LoginUser;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * 从 Security 上下文读取当前登录态。
 *
 * <p>业务代码取 accountId / shopId 一律走本类，禁止从请求参数取（防越权）。</p>
 */
public final class SecurityUtils {

    private SecurityUtils() {
    }

    /**
     * 当前登录用户。
     *
     * @throws BizException 401 未登录或登录态已失效
     */
    public static LoginUser getLoginUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof LoginUser loginUser) {
            return loginUser;
        }
        throw new BizException(ResultCode.UNAUTHORIZED.getCode(), ResultCode.UNAUTHORIZED.getMessage());
    }

    /** 当前登录账号 ID。 */
    public static Long getAccountId() {
        return getLoginUser().accountId();
    }

    /** 当前登录账号类型。 */
    public static AccountType getAccountType() {
        return getLoginUser().type();
    }

    /**
     * 当前登录商家的店铺 ID。
     *
     * <p>商家侧店铺/商品接口一律经此方法取 shopId（登录态绑定，禁止前端传参）；
     * 入驻审核未通过（shopId 为空）的商家调用时统一拒绝。</p>
     *
     * @throws BizException 入驻审核未通过，暂不能操作
     */
    public static Long getRequiredShopId() {
        Long shopId = getLoginUser().shopId();
        if (shopId == null) {
            throw new BizException("入驻审核未通过，暂不能操作");
        }
        return shopId;
    }
}
