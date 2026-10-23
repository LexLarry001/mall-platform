package com.mall.order.cart;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mall.common.exception.BizException;
import com.mall.order.cart.dto.CartItemView;
import com.mall.product.entity.ProductEntity;
import com.mall.product.entity.ProductSkuEntity;
import com.mall.product.entity.ShopEntity;
import com.mall.product.mapper.ProductMapper;
import com.mall.product.mapper.ProductSkuMapper;
import com.mall.product.mapper.ShopMapper;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 购物车服务（plan Task 2：仅 Redis Hash，不落 MySQL）。
 *
 * <p>结构：{@code mall:cart:{userId}} Hash，field=skuId，value=JSON {@code {quantity, checked}}。</p>
 *
 * <p>不变量：</p>
 * <ul>
 *   <li>加购仅接受在售（SKU 存在且 SPU status=2）商品；同款数量累加，勾选状态保留（新条目默认勾选）。</li>
 *   <li>列表实时联查 SKU/SPU/店铺；失效（SKU 删除 / SPU 未上架 / 库存 0）项打标
 *       {@code invalid=true}，仅打标不自动删除，不参与结算。</li>
 *   <li>坏 JSON 条目读取时自愈删除。</li>
 *   <li>Redis 故障统一转业务错误"购物车服务不可用，请稍后重试"（plan 决策 1：故障时购物车不可用可接受）。</li>
 * </ul>
 */
@Service
public class CartService {

    /** 购物车 Hash 键前缀，完整键为 {@code mall:cart:{userId}}。 */
    public static final String KEY_PREFIX = "mall:cart:";

    /** 商品状态：已上架（买家可见的唯一状态）。 */
    private static final int STATUS_ON_SHELF = 2;

    private static final String UNAVAILABLE = "购物车服务不可用，请稍后重试";

    private final StringRedisTemplate redis;
    private final ProductSkuMapper skuMapper;
    private final ProductMapper productMapper;
    private final ShopMapper shopMapper;
    private final ObjectMapper objectMapper;

    public CartService(StringRedisTemplate redis, ProductSkuMapper skuMapper,
                       ProductMapper productMapper, ShopMapper shopMapper, ObjectMapper objectMapper) {
        this.redis = redis;
        this.skuMapper = skuMapper;
        this.productMapper = productMapper;
        this.shopMapper = shopMapper;
        this.objectMapper = objectMapper;
    }

    /** 加购：SKU 在售校验 → 同款数量累加（新条目默认勾选，已有条目勾选状态保留）。 */
    public void add(Long userId, Long skuId, int quantity) {
        ProductSkuEntity sku = skuMapper.selectById(skuId);
        ProductEntity spu = sku == null ? null : productMapper.selectById(sku.getSpuId());
        if (sku == null || !onShelf(spu)) {
            throw new BizException("商品不存在或已下架");
        }
        String key = KEY_PREFIX + userId;
        String field = skuId.toString();
        CartEntry existing = readEntry(key, field);
        int merged = (existing == null ? 0 : existing.quantity()) + quantity;
        writeEntry(key, field, new CartEntry(merged, existing == null || existing.checked()));
    }

    /**
     * 我的购物车列表：保持 Redis Hash 条目顺序，实时联查 SKU/SPU/店铺，
     * 失效项打标 {@code invalid=true}；坏 JSON 条目自愈删除。
     */
    public List<CartItemView> list(Long userId) {
        String key = KEY_PREFIX + userId;
        Map<Object, Object> raw;
        try {
            raw = hashOps().entries(key);
        } catch (RuntimeException e) {
            throw new BizException(UNAVAILABLE);
        }
        if (raw == null || raw.isEmpty()) {
            return List.of();
        }

        Map<Long, CartEntry> parsed = new LinkedHashMap<>();
        List<Object> garbage = new ArrayList<>();
        for (Map.Entry<Object, Object> entry : raw.entrySet()) {
            CartEntry cartEntry = parseEntry((String) entry.getValue());
            if (cartEntry == null) {
                garbage.add(entry.getKey());
            } else {
                parsed.put(Long.valueOf((String) entry.getKey()), cartEntry);
            }
        }
        if (!garbage.isEmpty()) {
            try {
                hashOps().delete(key, garbage.toArray());
            } catch (RuntimeException e) {
                throw new BizException(UNAVAILABLE);
            }
        }
        if (parsed.isEmpty()) {
            return List.of();
        }

        Map<Long, ProductSkuEntity> skuById = skuMapper.selectBatchIds(parsed.keySet()).stream()
                .collect(Collectors.toMap(ProductSkuEntity::getId, Function.identity()));
        Map<Long, ProductEntity> spuById = skuById.isEmpty() ? Map.of() : productMapper.selectBatchIds(
                        skuById.values().stream().map(ProductSkuEntity::getSpuId).collect(Collectors.toSet()))
                .stream().collect(Collectors.toMap(ProductEntity::getId, Function.identity()));
        Map<Long, ShopEntity> shopById = spuById.isEmpty() ? Map.of() : shopMapper.selectBatchIds(
                        spuById.values().stream().map(ProductEntity::getShopId).collect(Collectors.toSet()))
                .stream().collect(Collectors.toMap(ShopEntity::getId, Function.identity()));

        List<CartItemView> items = new ArrayList<>(parsed.size());
        for (Map.Entry<Long, CartEntry> entry : parsed.entrySet()) {
            Long skuId = entry.getKey();
            CartEntry cartEntry = entry.getValue();
            ProductSkuEntity sku = skuById.get(skuId);
            ProductEntity spu = sku == null ? null : spuById.get(sku.getSpuId());
            ShopEntity shop = spu == null ? null : shopById.get(spu.getShopId());
            boolean invalid = sku == null || !onShelf(spu) || sku.getStock() == null || sku.getStock() <= 0;
            String img = sku != null && StringUtils.hasText(sku.getSkuImg()) ? sku.getSkuImg()
                    : spu == null ? null : spu.getMainImg();
            items.add(new CartItemView(
                    skuId,
                    sku == null ? null : sku.getSpuId(),
                    spu == null ? null : spu.getShopId(),
                    shop == null ? null : shop.getName(),
                    spu == null ? null : spu.getName(),
                    img,
                    sku == null ? null : sku.getSpecJson(),
                    sku == null ? null : sku.getPrice(),
                    sku == null ? null : sku.getStock(),
                    cartEntry.quantity(),
                    cartEntry.checked(),
                    invalid));
        }
        return items;
    }

    /** 修改条目：quantity / checked 缺省字段保持原值；购物车无该商品时业务错误。 */
    public void update(Long userId, Long skuId, Integer quantity, Boolean checked) {
        String key = KEY_PREFIX + userId;
        String field = skuId.toString();
        CartEntry existing = readEntry(key, field);
        if (existing == null) {
            throw new BizException("购物车中没有该商品");
        }
        writeEntry(key, field, new CartEntry(
                quantity == null ? existing.quantity() : quantity,
                checked == null ? existing.checked() : checked));
    }

    /** 移除条目（幂等，不存在不报错）。 */
    public void remove(Long userId, Long skuId) {
        try {
            hashOps().delete(KEY_PREFIX + userId, skuId.toString());
        } catch (RuntimeException e) {
            throw new BizException(UNAVAILABLE);
        }
    }

    /**
     * 读取勾选且未失效（可结算）的购物车条目（plan Task 3：结算与下单共用）。
     * 保持列表顺序。
     */
    public List<CartItemView> listChecked(Long userId) {
        return list(userId).stream()
                .filter(item -> item.checked() && !item.invalid())
                .toList();
    }

    /** 清空购物车中已勾选的条目（下单成功后调用）。 */
    public void removeChecked(Long userId) {
        String key = KEY_PREFIX + userId;
        Map<Object, Object> raw;
        try {
            raw = hashOps().entries(key);
        } catch (RuntimeException e) {
            throw new BizException(UNAVAILABLE);
        }
        if (raw == null || raw.isEmpty()) {
            return;
        }
        List<Object> checkedFields = new ArrayList<>();
        for (Map.Entry<Object, Object> entry : raw.entrySet()) {
            CartEntry cartEntry = parseEntry((String) entry.getValue());
            if (cartEntry != null && cartEntry.checked()) {
                checkedFields.add(entry.getKey());
            }
        }
        if (checkedFields.isEmpty()) {
            return;
        }
        try {
            hashOps().delete(key, checkedFields.toArray());
        } catch (RuntimeException e) {
            throw new BizException(UNAVAILABLE);
        }
    }

    private boolean onShelf(ProductEntity spu) {
        return spu != null && spu.getStatus() != null && spu.getStatus() == STATUS_ON_SHELF;
    }

    /** 读单条：不存在或坏 JSON 均返回 {@code null}（坏 JSON 由列表自愈清理）。 */
    private CartEntry readEntry(String key, String field) {
        Object value;
        try {
            value = hashOps().get(key, field);
        } catch (RuntimeException e) {
            throw new BizException(UNAVAILABLE);
        }
        return value == null ? null : parseEntry((String) value);
    }

    private CartEntry parseEntry(String json) {
        try {
            return objectMapper.readValue(json, CartEntry.class);
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    private void writeEntry(String key, String field, CartEntry entry) {
        String json;
        try {
            json = objectMapper.writeValueAsString(entry);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("购物车条目序列化失败", e);
        }
        try {
            hashOps().put(key, field, json);
        } catch (RuntimeException e) {
            throw new BizException(UNAVAILABLE);
        }
    }

    private HashOperations<String, Object, Object> hashOps() {
        return redis.opsForHash();
    }
}
