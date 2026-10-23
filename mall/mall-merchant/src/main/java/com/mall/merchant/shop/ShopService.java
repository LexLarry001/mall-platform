package com.mall.merchant.shop;

import com.mall.common.exception.BizException;
import com.mall.merchant.shop.dto.ShopInfoView;
import com.mall.merchant.shop.dto.ShopUpdateRequest;
import com.mall.product.entity.ShopEntity;
import com.mall.product.mapper.ShopMapper;
import org.springframework.stereotype.Service;

/**
 * 商家店铺管理。
 *
 * <p>所有方法只按登录态绑定的 shopId 操作（{@code SecurityUtils.getRequiredShopId()} 在
 * Controller 层取值），因此本服务内的任何读写天然限定在本店范围（数据级鉴权）。</p>
 */
@Service
public class ShopService {

    private static final int BIZ_STATUS_OPEN = 1;
    private static final int BIZ_STATUS_CLOSED = 0;

    private final ShopMapper shopMapper;

    public ShopService(ShopMapper shopMapper) {
        this.shopMapper = shopMapper;
    }

    /** 查询本店信息。 */
    public ShopInfoView info(Long shopId) {
        return ShopInfoView.from(requireShop(shopId));
    }

    /** 编辑本店信息，仅 name/logo/intro/notice 可变。 */
    public void updateInfo(Long shopId, ShopUpdateRequest request) {
        ShopEntity shop = requireShop(shopId);
        shop.setName(request.name());
        shop.setLogo(request.logo());
        shop.setIntro(request.intro());
        shop.setNotice(request.notice());
        shopMapper.updateById(shop);
    }

    /** 切换营业状态：1 营业 / 0 休店。 */
    public void changeBizStatus(Long shopId, Integer status) {
        if (status == null || (status != BIZ_STATUS_OPEN && status != BIZ_STATUS_CLOSED)) {
            throw new BizException(400, "营业状态仅支持 1 营业 / 0 休店");
        }
        ShopEntity shop = requireShop(shopId);
        shop.setBizStatus(status);
        shopMapper.updateById(shop);
    }

    private ShopEntity requireShop(Long shopId) {
        ShopEntity shop = shopMapper.selectById(shopId);
        if (shop == null) {
            throw new BizException("店铺不存在");
        }
        return shop;
    }
}
