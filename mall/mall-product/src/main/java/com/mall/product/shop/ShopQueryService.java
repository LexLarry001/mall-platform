package com.mall.product.shop;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.mall.common.exception.BizException;
import com.mall.product.entity.ProductEntity;
import com.mall.product.entity.ShopEntity;
import com.mall.product.mapper.ProductMapper;
import com.mall.product.mapper.ShopMapper;
import com.mall.product.shop.dto.ShopPublicView;
import org.springframework.stereotype.Service;

/**
 * 买家端店铺公开信息查询。
 *
 * <p>shop 表仅含"入驻审核通过"的店铺，查到即视为正常开店；productCount 仅统计
 * 已上架（status=2）商品，与买家可见口径一致。</p>
 */
@Service
public class ShopQueryService {

    /** 商品状态：已上架（买家可见）。 */
    private static final int PRODUCT_STATUS_ON_SHELF = 2;

    private final ShopMapper shopMapper;
    private final ProductMapper productMapper;

    public ShopQueryService(ShopMapper shopMapper, ProductMapper productMapper) {
        this.shopMapper = shopMapper;
        this.productMapper = productMapper;
    }

    /** 店铺公开信息 + 已上架商品数。 */
    public ShopPublicView publicInfo(Long shopId) {
        ShopEntity shop = shopMapper.selectById(shopId);
        if (shop == null) {
            throw new BizException("店铺不存在");
        }
        Long productCount = productMapper.selectCount(Wrappers.<ProductEntity>lambdaQuery()
                .eq(ProductEntity::getShopId, shopId)
                .eq(ProductEntity::getStatus, PRODUCT_STATUS_ON_SHELF));
        return new ShopPublicView(shop.getName(), shop.getLogo(), shop.getIntro(),
                shop.getBizStatus(), productCount);
    }
}
