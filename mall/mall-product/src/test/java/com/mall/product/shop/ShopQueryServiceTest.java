package com.mall.product.shop;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.mall.common.exception.BizException;
import com.mall.product.entity.ShopEntity;
import com.mall.product.mapper.ProductMapper;
import com.mall.product.mapper.ShopMapper;
import com.mall.product.shop.dto.ShopPublicView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * 买家端店铺查询服务单元测试（mapper mock）。
 *
 * <p>钉死 plan Task 4 买家公开接口语义：返回店铺公开信息 + 已上架商品数；
 * 查无店铺 → "店铺不存在"。productCount 的"仅 status=2"过滤由 HTTP 链路测试
 * （ShopAuthorizationTest）以真实数据钉死。</p>
 */
@ExtendWith(MockitoExtension.class)
class ShopQueryServiceTest {

    private static final Long SHOP_ID = 2001L;

    @Mock
    private ShopMapper shopMapper;
    @Mock
    private ProductMapper productMapper;

    private ShopQueryService shopQueryService;

    @BeforeEach
    void setUp() {
        shopQueryService = new ShopQueryService(shopMapper, productMapper);
    }

    @Test
    void publicInfo_should_aggregate_on_shelf_product_count() {
        when(shopMapper.selectById(SHOP_ID)).thenReturn(shop());
        when(productMapper.selectCount(any(Wrapper.class))).thenReturn(3L);

        ShopPublicView view = shopQueryService.publicInfo(SHOP_ID);

        assertThat(view.name()).isEqualTo("老街杂货铺");
        assertThat(view.logo()).isEqualTo("/static/logo.png");
        assertThat(view.intro()).isEqualTo("简介");
        assertThat(view.bizStatus()).isEqualTo(1);
        assertThat(view.productCount()).isEqualTo(3L);
    }

    @Test
    void publicInfo_should_throw_when_shop_missing() {
        when(shopMapper.selectById(SHOP_ID)).thenReturn(null);

        assertThatThrownBy(() -> shopQueryService.publicInfo(SHOP_ID))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("店铺不存在");
    }

    private ShopEntity shop() {
        ShopEntity shop = new ShopEntity();
        shop.setId(SHOP_ID);
        shop.setMerchantId(9001L);
        shop.setName("老街杂货铺");
        shop.setLogo("/static/logo.png");
        shop.setIntro("简介");
        shop.setBizStatus(1);
        return shop;
    }
}
