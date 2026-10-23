package com.mall.merchant.shop;

import com.mall.common.exception.BizException;
import com.mall.merchant.shop.dto.ShopInfoView;
import com.mall.merchant.shop.dto.ShopUpdateRequest;
import com.mall.product.entity.ShopEntity;
import com.mall.product.mapper.ShopMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 商家店铺管理服务单元测试（mapper mock）。
 *
 * <p>钉死 plan Task 4 Step 1 的店铺归属语义：</p>
 * <ul>
 *   <li>店铺信息按登录态 shopId 读取，查无店铺 → "店铺不存在"</li>
 *   <li>编辑仅允许 name/logo/intro/notice 四个字段，merchantId / bizStatus 不被请求覆盖（RF1 店铺侧）</li>
 *   <li>营业状态仅接受 1 营业 / 0 休店，其余值 400 且不落库</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class ShopServiceTest {

    private static final Long SHOP_ID = 2001L;

    @Mock
    private ShopMapper shopMapper;

    private ShopService shopService;

    @BeforeEach
    void setUp() {
        shopService = new ShopService(shopMapper);
    }

    @Test
    void info_should_return_view_when_shop_exists() {
        when(shopMapper.selectById(SHOP_ID)).thenReturn(shop());

        ShopInfoView view = shopService.info(SHOP_ID);

        assertThat(view.id()).isEqualTo(SHOP_ID);
        assertThat(view.name()).isEqualTo("老街杂货铺");
        assertThat(view.logo()).isEqualTo("/static/logo.png");
        assertThat(view.intro()).isEqualTo("原简介");
        assertThat(view.notice()).isEqualTo("原公告");
        assertThat(view.bizStatus()).isEqualTo(1);
    }

    @Test
    void info_should_throw_when_shop_missing() {
        when(shopMapper.selectById(SHOP_ID)).thenReturn(null);

        assertThatThrownBy(() -> shopService.info(SHOP_ID))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("店铺不存在");
    }

    @Test
    void updateInfo_should_only_touch_editable_fields() {
        when(shopMapper.selectById(SHOP_ID)).thenReturn(shop());

        shopService.updateInfo(SHOP_ID,
                new ShopUpdateRequest("新名字", "/static/new.png", "新简介", "新公告"));

        ArgumentCaptor<ShopEntity> captor = ArgumentCaptor.forClass(ShopEntity.class);
        verify(shopMapper).updateById(captor.capture());
        ShopEntity saved = captor.getValue();
        assertThat(saved.getId()).isEqualTo(SHOP_ID);
        assertThat(saved.getMerchantId()).isEqualTo(9001L);
        assertThat(saved.getName()).isEqualTo("新名字");
        assertThat(saved.getLogo()).isEqualTo("/static/new.png");
        assertThat(saved.getIntro()).isEqualTo("新简介");
        assertThat(saved.getNotice()).isEqualTo("新公告");
        assertThat(saved.getBizStatus()).isEqualTo(1);
    }

    @Test
    void updateInfo_should_throw_when_shop_missing() {
        when(shopMapper.selectById(SHOP_ID)).thenReturn(null);

        assertThatThrownBy(() -> shopService.updateInfo(SHOP_ID,
                new ShopUpdateRequest("新名字", null, null, null)))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("店铺不存在");
        verify(shopMapper, never()).updateById(any(ShopEntity.class));
    }

    @Test
    void changeBizStatus_should_update_when_status_valid() {
        when(shopMapper.selectById(SHOP_ID)).thenReturn(shop());

        shopService.changeBizStatus(SHOP_ID, 0);

        ArgumentCaptor<ShopEntity> captor = ArgumentCaptor.forClass(ShopEntity.class);
        verify(shopMapper).updateById(captor.capture());
        assertThat(captor.getValue().getId()).isEqualTo(SHOP_ID);
        assertThat(captor.getValue().getBizStatus()).isEqualTo(0);
    }

    @Test
    void changeBizStatus_should_reject_invalid_status() {
        assertThatThrownBy(() -> shopService.changeBizStatus(SHOP_ID, 2))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("营业状态");
        assertThatThrownBy(() -> shopService.changeBizStatus(SHOP_ID, null))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("营业状态");
        verify(shopMapper, never()).updateById(any(ShopEntity.class));
    }

    @Test
    void changeBizStatus_should_throw_when_shop_missing() {
        when(shopMapper.selectById(SHOP_ID)).thenReturn(null);

        assertThatThrownBy(() -> shopService.changeBizStatus(SHOP_ID, 1))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("店铺不存在");
    }

    private ShopEntity shop() {
        ShopEntity shop = new ShopEntity();
        shop.setId(SHOP_ID);
        shop.setMerchantId(9001L);
        shop.setName("老街杂货铺");
        shop.setLogo("/static/logo.png");
        shop.setIntro("原简介");
        shop.setNotice("原公告");
        shop.setBizStatus(1);
        return shop;
    }
}
