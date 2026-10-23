<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import BuyerHeader from '../components/BuyerHeader.vue'
import ProductReviews from '../components/ProductReviews.vue'
import { getProductDetail } from '../api/product'
import type { ProductDetail } from '../api/product'
import { addToCart, listCart, updateCartItem } from '../api/cart'
import { useAuthStore } from '../stores/auth'
import { buildSpecMatrix, matchSku } from '../utils/sku-match'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()
const spuId = typeof route.params.id === 'string' ? route.params.id : ''

const detail = ref<ProductDetail | null>(null)
const loading = ref(true)
const errorMsg = ref('')

const quantity = ref(1)
const adding = ref(false)
const buying = ref(false)

const activeImg = ref('')
const selected = ref<Record<string, string>>({})

const gallery = computed(() => {
  if (!detail.value) return [] as string[]
  const list = [detail.value.spu.mainImg, ...(detail.value.spu.imgList || [])]
  return [...new Set(list)]
})

const specMatrix = computed(() =>
  detail.value ? buildSpecMatrix(detail.value.specs, detail.value.skus, selected.value) : {},
)

const matchedSku = computed(() => (detail.value ? matchSku(detail.value.skus, selected.value) : null))

const displayPrice = computed(() => {
  if (matchedSku.value) return Number(matchedSku.value.price).toFixed(2)
  return detail.value ? Number(detail.value.spu.price).toFixed(2) : '-'
})

const displayStock = computed(() => {
  if (matchedSku.value) return matchedSku.value.stock
  if (!detail.value) return 0
  return detail.value.skus.reduce((sum, s) => sum + s.stock, 0)
})

async function onAddToCart(): Promise<void> {
  if (!auth.token) {
    await router.push({ name: 'login', query: { redirect: route.fullPath } })
    return
  }
  const sku = matchedSku.value
  if (!sku) {
    alert('请先选择完整规格')
    return
  }
  if (sku.stock <= 0) {
    alert('该规格暂时缺货')
    return
  }
  if (adding.value) return
  adding.value = true
  try {
    await addToCart(sku.id, quantity.value)
    if (confirm('已加入购物车，是否前往购物车查看？')) {
      await router.push({ name: 'cart' })
    }
  } catch (e) {
    alert(e instanceof Error ? e.message : '加入购物车失败')
  } finally {
    adding.value = false
  }
}

async function onBuyNow(): Promise<void> {
  if (!auth.token) {
    await router.push({ name: 'login', query: { redirect: route.fullPath } })
    return
  }
  const sku = matchedSku.value
  if (!sku) {
    alert('请先选择完整规格')
    return
  }
  if (sku.stock <= 0) {
    alert('该规格暂时缺货')
    return
  }
  if (buying.value) return
  buying.value = true
  try {
    await addToCart(sku.id, quantity.value)
    // 立即购买只结算当前 SKU：取消勾选购物车内其他条目
    const items = await listCart()
    for (const item of items) {
      const shouldCheck = item.skuId === sku.id
      if (item.checked !== shouldCheck) {
        await updateCartItem(item.skuId, { checked: shouldCheck })
      }
    }
    await router.push({ name: 'checkout' })
  } catch (e) {
    alert(e instanceof Error ? e.message : '立即购买失败')
  } finally {
    buying.value = false
  }
}

function onSelectSpec(specName: string, value: string, disabled: boolean): void {
  if (disabled) return
  if (selected.value[specName] === value) {
    const next = { ...selected.value }
    delete next[specName]
    selected.value = next
    return
  }
  selected.value = { ...selected.value, [specName]: value }
  const skuImg = matchedSku.value?.skuImg
  if (skuImg) activeImg.value = skuImg
}

onMounted(async () => {
  try {
    detail.value = await getProductDetail(spuId)
    activeImg.value = detail.value.spu.mainImg
  } catch (e) {
    errorMsg.value = e instanceof Error ? e.message : '商品不存在或已下架'
  } finally {
    loading.value = false
  }
})
</script>

<template>
  <div class="detail-page">
    <BuyerHeader />
    <div class="detail-page__body">
      <div v-if="loading" class="placeholder">加载中…</div>

      <div v-else-if="errorMsg" class="not-found">
        <h3>商品不存在或已下架</h3>
        <p>{{ errorMsg }}</p>
        <RouterLink class="not-found__link" :to="{ name: 'home' }">返回首页</RouterLink>
      </div>

      <template v-else-if="detail">
        <div class="main-panel">
          <div class="gallery">
            <img :src="activeImg" :alt="detail.spu.name" class="gallery__main" />
            <div class="gallery__thumbs">
              <img
                v-for="img in gallery"
                :key="img"
                :src="img"
                :alt="detail.spu.name"
                class="gallery__thumb"
                :class="{ 'gallery__thumb--active': activeImg === img }"
                @mouseenter="activeImg = img"
                @click="activeImg = img"
              />
            </div>
          </div>

          <div class="info">
            <h2 class="info__name">{{ detail.spu.name }}</h2>
            <p v-if="detail.spu.subTitle" class="info__sub">{{ detail.spu.subTitle }}</p>

            <div class="info__price-row">
              <span class="info__price">¥ {{ displayPrice }}</span>
              <span v-if="!matchedSku" class="info__price-tip">起（选择规格后显示准确价格）</span>
            </div>

            <div class="info__meta">
              <span>已售 {{ detail.spu.sales }}</span>
              <span>库存 {{ displayStock }}</span>
              <span>评分 {{ Number(detail.spu.avgRating).toFixed(1) }}（{{ detail.spu.commentCount }} 条评价）</span>
            </div>

            <div v-for="spec in detail.specs" :key="spec.specName" class="spec">
              <div class="spec__name">{{ spec.specName }}</div>
              <div class="spec__values">
                <button
                  v-for="v in specMatrix[spec.specName] || []"
                  :key="v.value"
                  class="spec__value"
                  :class="{
                    'spec__value--active': selected[spec.specName] === v.value,
                    'spec__value--disabled': v.disabled,
                  }"
                  :disabled="v.disabled"
                  @click="onSelectSpec(spec.specName, v.value, v.disabled)"
                >
                  {{ v.value }}
                </button>
              </div>
            </div>

            <div class="info__qty">
              <span class="info__qty-label">数量</span>
              <button class="qty-btn" :disabled="quantity <= 1" @click="quantity--">−</button>
              <span class="qty-num">{{ quantity }}</span>
              <button
                class="qty-btn"
                :disabled="matchedSku !== null && quantity >= matchedSku.stock"
                @click="quantity++"
              >
                ＋
              </button>
            </div>

            <div class="info__actions">
              <button
                class="btn btn--ghost"
                :disabled="adding || buying || (matchedSku !== null && matchedSku.stock <= 0)"
                @click="onAddToCart"
              >
                {{ adding ? '加入中…' : '加入购物车' }}
              </button>
              <button
                class="btn btn--primary"
                :disabled="adding || buying || (matchedSku !== null && matchedSku.stock <= 0)"
                @click="onBuyNow"
              >
                {{ buying ? '处理中…' : '立即购买' }}
              </button>
            </div>
          </div>
        </div>

        <div class="shop-card">
          <img v-if="detail.shop.logo" :src="detail.shop.logo" :alt="detail.shop.name" class="shop-card__logo" />
          <div class="shop-card__info">
            <div class="shop-card__name">
              {{ detail.shop.name }}
              <span v-if="detail.shop.bizStatus !== 1" class="shop-card__rest">休息中</span>
            </div>
            <div class="shop-card__intro">{{ detail.shop.intro || '这家店很懒，什么都没有留下。' }}</div>
          </div>
          <RouterLink class="shop-card__link" :to="{ name: 'shop', params: { id: detail.shop.id } }">
            进店逛逛
          </RouterLink>
        </div>

        <div class="detail-html">
          <h3>商品详情</h3>
          <!-- 后端富文本来自受控商家端编辑器，此处以容器样式隔离渲染 -->
          <div class="detail-html__content" v-html="detail.detailHtml || '<p>暂无图文详情</p>'"></div>
        </div>

        <ProductReviews :spu-id="spuId" />
      </template>
    </div>
  </div>
</template>

<style scoped>
.detail-page__body {
  max-width: 1200px;
  margin: 0 auto;
  padding: 20px 16px 40px;
}

.placeholder {
  padding: 80px 0;
  text-align: center;
  color: var(--wood-accent);
}

.not-found {
  text-align: center;
  padding: 80px 0;
}

.not-found p {
  color: var(--wood-accent);
  font-size: 14px;
}

.not-found__link {
  color: var(--wood-primary);
  text-decoration: none;
}

.main-panel {
  display: flex;
  gap: 28px;
  background: var(--wood-surface);
  border: 1px solid var(--wood-border);
  border-radius: 12px;
  padding: 24px;
}

.gallery {
  width: 420px;
  flex-shrink: 0;
}

.gallery__main {
  width: 100%;
  aspect-ratio: 1;
  object-fit: cover;
  border-radius: 10px;
  border: 1px solid var(--wood-border);
  background: var(--wood-bg);
  display: block;
}

.gallery__thumbs {
  display: flex;
  gap: 8px;
  margin-top: 10px;
  flex-wrap: wrap;
}

.gallery__thumb {
  width: 64px;
  height: 64px;
  object-fit: cover;
  border-radius: 6px;
  border: 2px solid var(--wood-border);
  cursor: pointer;
  background: var(--wood-bg);
}

.gallery__thumb--active {
  border-color: var(--wood-primary);
}

.info {
  flex: 1;
  min-width: 0;
}

.info__name {
  margin: 0 0 6px;
  font-size: 22px;
}

.info__sub {
  margin: 0 0 14px;
  color: var(--wood-accent);
  font-size: 14px;
}

.info__price-row {
  background: #fdf1e7;
  border-radius: 8px;
  padding: 14px 16px;
  margin-bottom: 14px;
  display: flex;
  align-items: baseline;
  gap: 10px;
}

.info__price {
  color: var(--wood-primary-dark);
  font-size: 28px;
  font-weight: bold;
}

.info__price-tip {
  color: var(--wood-accent);
  font-size: 12px;
}

.info__meta {
  display: flex;
  gap: 18px;
  color: var(--wood-accent);
  font-size: 13px;
  margin-bottom: 18px;
}

.spec {
  margin-bottom: 14px;
}

.spec__name {
  font-size: 13px;
  color: var(--wood-primary-dark);
  margin-bottom: 8px;
}

.spec__values {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
}

.spec__value {
  padding: 8px 16px;
  border: 1px solid var(--wood-border);
  border-radius: 8px;
  background: #fff;
  color: var(--wood-text);
  font-size: 13px;
  cursor: pointer;
}

.spec__value--active {
  border-color: var(--wood-primary);
  color: var(--wood-primary-dark);
  background: #fdf1e7;
}

.spec__value--disabled {
  opacity: 0.4;
  cursor: not-allowed;
  text-decoration: line-through;
}

.info__qty {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-top: 6px;
}

.info__qty-label {
  font-size: 13px;
  color: var(--wood-primary-dark);
  margin-right: 6px;
}

.qty-btn {
  width: 30px;
  height: 30px;
  border: 1px solid var(--wood-border);
  background: #fff;
  border-radius: 6px;
  cursor: pointer;
  font-size: 15px;
  color: var(--wood-primary-dark);
}

.qty-btn:disabled {
  opacity: 0.4;
  cursor: not-allowed;
}

.qty-num {
  min-width: 40px;
  text-align: center;
  font-size: 15px;
}

.info__actions {
  display: flex;
  gap: 12px;
  margin-top: 22px;
}

.btn {
  height: 44px;
  padding: 0 28px;
  border-radius: 8px;
  font-size: 15px;
  cursor: pointer;
}

.btn--primary {
  border: 1px solid var(--wood-primary);
  background: var(--wood-primary);
  color: #fff;
}

.btn--ghost {
  border: 1px solid var(--wood-primary);
  background: #fff;
  color: var(--wood-primary-dark);
}

.btn--ghost:hover:not(:disabled) {
  background: #fdf1e7;
}

.btn:disabled {
  opacity: 0.55;
  cursor: not-allowed;
}

.shop-card {
  display: flex;
  align-items: center;
  gap: 16px;
  background: var(--wood-surface);
  border: 1px solid var(--wood-border);
  border-radius: 12px;
  padding: 18px 24px;
  margin-top: 20px;
}

.shop-card__logo {
  width: 56px;
  height: 56px;
  border-radius: 50%;
  object-fit: cover;
  border: 1px solid var(--wood-border);
}

.shop-card__info {
  flex: 1;
  min-width: 0;
}

.shop-card__name {
  font-size: 16px;
  font-weight: bold;
  color: var(--wood-primary-dark);
}

.shop-card__rest {
  margin-left: 8px;
  font-size: 12px;
  color: var(--wood-accent);
  border: 1px solid var(--wood-accent);
  border-radius: 999px;
  padding: 2px 8px;
  font-weight: normal;
}

.shop-card__intro {
  font-size: 13px;
  color: var(--wood-accent);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.shop-card__link {
  flex-shrink: 0;
  height: 36px;
  line-height: 36px;
  padding: 0 18px;
  border: 1px solid var(--wood-primary);
  border-radius: 8px;
  color: var(--wood-primary);
  text-decoration: none;
  font-size: 13px;
}

.shop-card__link:hover {
  background: var(--wood-primary);
  color: #fff;
}

.detail-html {
  background: var(--wood-surface);
  border: 1px solid var(--wood-border);
  border-radius: 12px;
  padding: 24px;
  margin-top: 20px;
}

.detail-html h3 {
  margin: 0 0 16px;
}

.detail-html__content {
  font-size: 14px;
  line-height: 1.8;
  color: var(--wood-text);
  overflow-wrap: break-word;
}

.detail-html__content :deep(img) {
  max-width: 100%;
  height: auto;
}
</style>
