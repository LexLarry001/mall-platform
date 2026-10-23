package com.mall.order.cart;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mall.common.exception.BizException;
import com.mall.order.cart.dto.CartItemView;
import com.mall.product.entity.ProductEntity;
import com.mall.product.entity.ProductSkuEntity;
import com.mall.product.entity.ShopEntity;
import com.mall.product.mapper.ProductMapper;
import com.mall.product.mapper.ProductSkuMapper;
import com.mall.product.mapper.ShopMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 购物车服务单元测试（Redis Hash + mapper 全 mock）。
 *
 * <p>钉死 plan Task 2 语义：加购同款合并数量；失效（下架/删除/库存 0）项打标 invalid；
 * 勾选状态随 value JSON 序列化；Redis 故障统一转业务错误"购物车服务不可用，请稍后重试"。</p>
 */
@ExtendWith(MockitoExtension.class)
class CartServiceTest {

    private static final Long USER_ID = 101L;
    private static final String CART_KEY = CartService.KEY_PREFIX + USER_ID;

    @Mock
    private StringRedisTemplate redis;
    @Mock
    private HashOperations<String, Object, Object> hashOps;
    @Mock
    private ProductSkuMapper skuMapper;
    @Mock
    private ProductMapper productMapper;
    @Mock
    private ShopMapper shopMapper;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private CartService cartService;

    @BeforeEach
    void setUp() {
        lenient().when(redis.opsForHash()).thenReturn(hashOps);
        cartService = new CartService(redis, skuMapper, productMapper, shopMapper, objectMapper);
    }

    /** 加购新 SKU：写入 Hash，JSON 为 {quantity, checked:true}（勾选默认 true）。 */
    @Test
    void add_new_sku_writes_entry_checked_true() throws Exception {
        stubOnShelfSku(5001L, 6001L, 10);

        cartService.add(USER_ID, 5001L, 2);

        ArgumentCaptor<String> jsonCaptor = ArgumentCaptor.forClass(String.class);
        verify(hashOps).put(eq(CART_KEY), eq("5001"), jsonCaptor.capture());
        JsonNode json = objectMapper.readTree(jsonCaptor.getValue());
        assertThat(json.get("quantity").asInt()).isEqualTo(2);
        assertThat(json.get("checked").asBoolean()).isTrue();
    }

    /** 同款合并：数量累加，原勾选状态保留。 */
    @Test
    void add_merges_quantity_and_preserves_checked() throws Exception {
        stubOnShelfSku(5001L, 6001L, 10);
        when(hashOps.get(CART_KEY, "5001")).thenReturn("{\"quantity\":2,\"checked\":false}");

        cartService.add(USER_ID, 5001L, 3);

        ArgumentCaptor<String> jsonCaptor = ArgumentCaptor.forClass(String.class);
        verify(hashOps).put(eq(CART_KEY), eq("5001"), jsonCaptor.capture());
        JsonNode json = objectMapper.readTree(jsonCaptor.getValue());
        assertThat(json.get("quantity").asInt()).isEqualTo(5);
        assertThat(json.get("checked").asBoolean()).isFalse();
    }

    /** 加购不存在的 SKU：业务错误且不写 Redis。 */
    @Test
    void add_rejects_missing_sku() {
        when(skuMapper.selectById(9999L)).thenReturn(null);

        assertThatThrownBy(() -> cartService.add(USER_ID, 9999L, 1))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("商品不存在或已下架");
        verify(hashOps, never()).put(any(), any(), any());
    }

    /** 加购未上架商品的 SKU：业务错误且不写 Redis。 */
    @Test
    void add_rejects_off_shelf_spu() {
        ProductSkuEntity sku = sku(5001L, 6001L, 10);
        when(skuMapper.selectById(5001L)).thenReturn(sku);
        ProductEntity spu = spu(6001L, 4, 7001L);
        when(productMapper.selectById(6001L)).thenReturn(spu);

        assertThatThrownBy(() -> cartService.add(USER_ID, 5001L, 1))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("商品不存在或已下架");
        verify(hashOps, never()).put(any(), any(), any());
    }

    /** 列表：有效项完整映射（店铺/价格/SKU 图回退主图），失效项打标 invalid。 */
    @Test
    void list_marks_invalid_and_maps_valid() {
        Map<Object, Object> raw = new LinkedHashMap<>();
        raw.put("5001", "{\"quantity\":1,\"checked\":true}");
        raw.put("5002", "{\"quantity\":2,\"checked\":true}");
        raw.put("5003", "{\"quantity\":3,\"checked\":false}");
        raw.put("5004", "{\"quantity\":1,\"checked\":true}");
        when(hashOps.entries(CART_KEY)).thenReturn(raw);

        ProductSkuEntity sku1 = sku(5001L, 6001L, 5);
        sku1.setPrice(new BigDecimal("19.90"));
        ProductSkuEntity sku2 = sku(5002L, 6001L, 0);
        sku2.setPrice(new BigDecimal("29.90"));
        ProductSkuEntity sku3 = sku(5003L, 6002L, 3);
        sku3.setSkuImg(null);
        when(skuMapper.selectBatchIds(any())).thenReturn(List.of(sku1, sku2, sku3));

        ProductEntity spu1 = spu(6001L, 2, 7001L);
        spu1.setName("原木杯");
        spu1.setMainImg("main1.jpg");
        ProductEntity spu2 = spu(6002L, 4, 7001L);
        when(productMapper.selectBatchIds(any())).thenReturn(List.of(spu1, spu2));

        ShopEntity shop = new ShopEntity();
        shop.setId(7001L);
        shop.setName("木艺小店");
        when(shopMapper.selectBatchIds(any())).thenReturn(List.of(shop));

        List<CartItemView> items = cartService.list(USER_ID);

        assertThat(items).hasSize(4);

        CartItemView valid = items.get(0);
        assertThat(valid.skuId()).isEqualTo(5001L);
        assertThat(valid.spuId()).isEqualTo(6001L);
        assertThat(valid.shopId()).isEqualTo(7001L);
        assertThat(valid.shopName()).isEqualTo("木艺小店");
        assertThat(valid.productName()).isEqualTo("原木杯");
        assertThat(valid.skuImg()).isEqualTo("sku_5001.jpg");
        assertThat(valid.price()).isEqualByComparingTo("19.90");
        assertThat(valid.stock()).isEqualTo(5);
        assertThat(valid.quantity()).isEqualTo(1);
        assertThat(valid.checked()).isTrue();
        assertThat(valid.invalid()).isFalse();

        CartItemView stockZero = items.get(1);
        assertThat(stockZero.skuId()).isEqualTo(5002L);
        assertThat(stockZero.invalid()).isTrue();

        CartItemView offShelf = items.get(2);
        assertThat(offShelf.skuId()).isEqualTo(5003L);
        assertThat(offShelf.checked()).isFalse();
        assertThat(offShelf.invalid()).isTrue();

        CartItemView skuMissing = items.get(3);
        assertThat(skuMissing.skuId()).isEqualTo(5004L);
        assertThat(skuMissing.spuId()).isNull();
        assertThat(skuMissing.invalid()).isTrue();
    }

    /** SKU 图缺失时回退 SPU 主图。 */
    @Test
    void list_falls_back_to_spu_main_img() {
        Map<Object, Object> raw = new LinkedHashMap<>();
        raw.put("5003", "{\"quantity\":1,\"checked\":true}");
        when(hashOps.entries(CART_KEY)).thenReturn(raw);

        ProductSkuEntity sku3 = sku(5003L, 6001L, 3);
        sku3.setSkuImg(null);
        when(skuMapper.selectBatchIds(any())).thenReturn(List.of(sku3));
        ProductEntity spu1 = spu(6001L, 2, 7001L);
        spu1.setMainImg("main1.jpg");
        when(productMapper.selectBatchIds(any())).thenReturn(List.of(spu1));
        ShopEntity shop = new ShopEntity();
        shop.setId(7001L);
        when(shopMapper.selectBatchIds(any())).thenReturn(List.of(shop));

        List<CartItemView> items = cartService.list(USER_ID);

        assertThat(items).hasSize(1);
        assertThat(items.get(0).skuImg()).isEqualTo("main1.jpg");
        assertThat(items.get(0).invalid()).isFalse();
    }

    /** 列表：坏 JSON 条目自愈删除且不进入结果。 */
    @Test
    void list_removes_unparseable_entries() {
        Map<Object, Object> raw = new LinkedHashMap<>();
        raw.put("9999", "not-json");
        raw.put("5001", "{\"quantity\":1,\"checked\":true}");
        when(hashOps.entries(CART_KEY)).thenReturn(raw);

        ProductSkuEntity sku1 = sku(5001L, 6001L, 5);
        when(skuMapper.selectBatchIds(any())).thenReturn(List.of(sku1));
        when(productMapper.selectBatchIds(any())).thenReturn(List.of(spu(6001L, 2, 7001L)));
        ShopEntity shop = new ShopEntity();
        shop.setId(7001L);
        when(shopMapper.selectBatchIds(any())).thenReturn(List.of(shop));

        List<CartItemView> items = cartService.list(USER_ID);

        assertThat(items).hasSize(1);
        assertThat(items.get(0).skuId()).isEqualTo(5001L);
        verify(hashOps).delete(CART_KEY, "9999");
    }

    /** 改数量/勾选：购物车无该商品时业务错误。 */
    @Test
    void update_requires_existing_entry() {
        when(hashOps.get(CART_KEY, "5001")).thenReturn(null);

        assertThatThrownBy(() -> cartService.update(USER_ID, 5001L, 3, null))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("购物车中没有该商品");
    }

    /** 改数量：只改数量，勾选状态保留。 */
    @Test
    void update_changes_quantity_keeps_checked() throws Exception {
        when(hashOps.get(CART_KEY, "5001")).thenReturn("{\"quantity\":2,\"checked\":false}");

        cartService.update(USER_ID, 5001L, 5, null);

        ArgumentCaptor<String> jsonCaptor = ArgumentCaptor.forClass(String.class);
        verify(hashOps).put(eq(CART_KEY), eq("5001"), jsonCaptor.capture());
        JsonNode json = objectMapper.readTree(jsonCaptor.getValue());
        assertThat(json.get("quantity").asInt()).isEqualTo(5);
        assertThat(json.get("checked").asBoolean()).isFalse();
    }

    /** 改勾选：只改勾选，数量保留。 */
    @Test
    void update_changes_checked_keeps_quantity() throws Exception {
        when(hashOps.get(CART_KEY, "5001")).thenReturn("{\"quantity\":2,\"checked\":false}");

        cartService.update(USER_ID, 5001L, null, true);

        ArgumentCaptor<String> jsonCaptor = ArgumentCaptor.forClass(String.class);
        verify(hashOps).put(eq(CART_KEY), eq("5001"), jsonCaptor.capture());
        JsonNode json = objectMapper.readTree(jsonCaptor.getValue());
        assertThat(json.get("quantity").asInt()).isEqualTo(2);
        assertThat(json.get("checked").asBoolean()).isTrue();
    }

    /** 移除：删除 Hash field。 */
    @Test
    void remove_deletes_hash_field() {
        cartService.remove(USER_ID, 5001L);

        verify(hashOps).delete(CART_KEY, "5001");
    }

    /** Redis 故障（加购）：统一转业务错误。 */
    @Test
    void redis_failure_on_add_becomes_biz_exception() {
        stubOnShelfSku(5001L, 6001L, 10);
        when(hashOps.get(CART_KEY, "5001"))
                .thenThrow(new RedisConnectionFailureException("connection refused"));

        assertThatThrownBy(() -> cartService.add(USER_ID, 5001L, 1))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("购物车服务不可用");
    }

    /** Redis 故障（列表）：统一转业务错误。 */
    @Test
    void redis_failure_on_list_becomes_biz_exception() {
        when(hashOps.entries(CART_KEY))
                .thenThrow(new RedisConnectionFailureException("connection refused"));

        assertThatThrownBy(() -> cartService.list(USER_ID))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("购物车服务不可用");
    }

    private void stubOnShelfSku(Long skuId, Long spuId, int stock) {
        when(skuMapper.selectById(skuId)).thenReturn(sku(skuId, spuId, stock));
        when(productMapper.selectById(spuId)).thenReturn(spu(spuId, 2, 7001L));
    }

    private ProductSkuEntity sku(Long id, Long spuId, int stock) {
        ProductSkuEntity sku = new ProductSkuEntity();
        sku.setId(id);
        sku.setSpuId(spuId);
        sku.setSpecJson("{\"颜色\":\"原木色\"}");
        sku.setPrice(new BigDecimal("9.90"));
        sku.setStock(stock);
        sku.setSkuImg("sku_" + id + ".jpg");
        return sku;
    }

    private ProductEntity spu(Long id, int status, Long shopId) {
        ProductEntity spu = new ProductEntity();
        spu.setId(id);
        spu.setShopId(shopId);
        spu.setName("商品" + id);
        spu.setMainImg("main_" + id + ".jpg");
        spu.setStatus(status);
        return spu;
    }
}
