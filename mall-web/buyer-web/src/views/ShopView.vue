<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import BuyerHeader from '../components/BuyerHeader.vue'
import { getCategoryTree } from '../api/category'
import type { CategoryNode } from '../api/category'
import { searchProducts } from '../api/product'
import type { PageResult, ProductItem, ProductSort } from '../api/product'
import { getShopInfo } from '../api/shop'
import type { ShopInfo } from '../api/shop'

const route = useRoute()

const shopId = computed(() => (typeof route.params.id === 'string' ? route.params.id : ''))
const shop = ref<ShopInfo | null>(null)
const shopError = ref('')

const categories = ref<CategoryNode[]>([])
const result = ref<PageResult<ProductItem>>({ records: [], total: 0, current: 1, size: 12 })
const loading = ref(false)
const errorMsg = ref('')

const categoryId = ref<string>('')
const sort = ref<ProductSort>('default')
const page = ref(1)

const SORT_TABS: Array<{ key: ProductSort; label: string }> = [
  { key: 'default', label: '综合' },
  { key: 'sales', label: '销量' },
  { key: 'price_asc', label: '价格 ↑' },
  { key: 'price_desc', label: '价格 ↓' },
  { key: 'new', label: '新品' },
]

const totalPages = computed(() => Math.max(1, Math.ceil(result.value.total / result.value.size)))

async function loadShop(): Promise<void> {
  shopError.value = ''
  if (!shopId.value) {
    shopError.value = '店铺不存在'
    return
  }
  try {
    shop.value = await getShopInfo(shopId.value)
  } catch (e) {
    shopError.value = e instanceof Error ? e.message : '店铺信息加载失败'
  }
}

async function loadProducts(): Promise<void> {
  loading.value = true
  errorMsg.value = ''
  try {
    result.value = await searchProducts({
      shopId: shopId.value,
      categoryId: categoryId.value || undefined,
      sort: sort.value,
      page: page.value,
      size: 12,
    })
  } catch (e) {
    errorMsg.value = e instanceof Error ? e.message : '商品加载失败'
  } finally {
    loading.value = false
  }
}

function onCategory(id: string): void {
  categoryId.value = id
  page.value = 1
  void loadProducts()
}

function onSort(s: ProductSort): void {
  sort.value = s
  page.value = 1
  void loadProducts()
}

function onPage(delta: number): void {
  const next = page.value + delta
  if (next < 1 || next > totalPages.value) return
  page.value = next
  void loadProducts()
}

watch(
  () => route.params.id,
  () => {
    categoryId.value = ''
    sort.value = 'default'
    page.value = 1
    void loadShop()
    void loadProducts()
  },
)

onMounted(async () => {
  void loadShop()
  void loadProducts()
  try {
    categories.value = await getCategoryTree()
  } catch {
    // 类目加载失败不阻塞店铺商品列表
  }
})
</script>

<template>
  <div class="shop-page">
    <BuyerHeader :show-search="false" />

    <div class="shop-page__body">
      <div v-if="shopError" class="placeholder">{{ shopError }}</div>

      <template v-else>
        <section class="shop-hero">
          <img v-if="shop?.logo" :src="shop.logo" :alt="shop?.name" class="shop-hero__logo" />
          <div v-else class="shop-hero__logo shop-hero__logo--empty">店</div>
          <div class="shop-hero__info">
            <h2 class="shop-hero__name">
              {{ shop?.name || '店铺' }}
              <span v-if="shop && shop.bizStatus !== 1" class="shop-hero__rest">休息中</span>
            </h2>
            <p class="shop-hero__intro">{{ shop?.intro || '这家店很懒，什么都没有留下。' }}</p>
            <span class="shop-hero__count">在售商品 {{ shop?.productCount ?? 0 }} 件</span>
          </div>
          <RouterLink class="shop-hero__back" :to="{ name: 'home' }">返回商城首页</RouterLink>
        </section>

        <nav class="cat-strip">
          <button
            class="cat-strip__item"
            :class="{ 'cat-strip__item--active': !categoryId }"
            @click="onCategory('')"
          >
            全部
          </button>
          <button
            v-for="l1 in categories"
            :key="l1.id"
            class="cat-strip__item"
            :class="{ 'cat-strip__item--active': categoryId === String(l1.id) }"
            @click="onCategory(String(l1.id))"
          >
            {{ l1.name }}
          </button>
        </nav>

        <div class="list-head">
          <h3>本店商品</h3>
          <span class="list-head__total">共 {{ result.total }} 件</span>
        </div>

        <div class="sort-bar">
          <button
            v-for="t in SORT_TABS"
            :key="t.key"
            class="sort-bar__tab"
            :class="{ 'sort-bar__tab--active': sort === t.key }"
            @click="onSort(t.key)"
          >
            {{ t.label }}
          </button>
        </div>

        <div v-if="errorMsg" class="alert">{{ errorMsg }}</div>
        <div v-if="loading" class="placeholder">加载中…</div>
        <div v-else-if="result.records.length === 0" class="placeholder">
          本店暂无符合条件的商品
        </div>
        <div v-else class="product-grid">
          <RouterLink
            v-for="p in result.records"
            :key="p.spuId"
            class="product-card"
            :to="{ name: 'productDetail', params: { id: p.spuId } }"
          >
            <img :src="p.mainImg" :alt="p.name" class="product-card__img" />
            <div class="product-card__name">{{ p.name }}</div>
            <div class="product-card__meta">
              <span class="product-card__price">¥ {{ Number(p.price).toFixed(2) }}</span>
              <span class="product-card__sales">已售 {{ p.sales }}</span>
            </div>
          </RouterLink>
        </div>

        <div v-if="result.total > 0" class="pager">
          <button class="pager__btn" :disabled="page <= 1" @click="onPage(-1)">上一页</button>
          <span class="pager__info">{{ page }} / {{ totalPages }}</span>
          <button class="pager__btn" :disabled="page >= totalPages" @click="onPage(1)">下一页</button>
        </div>
      </template>
    </div>
  </div>
</template>

<style scoped>
.shop-page {
  min-height: 100vh;
  background: var(--wood-bg);
}

.shop-page__body {
  max-width: 1200px;
  margin: 0 auto;
  padding: 16px 16px 40px;
}

.shop-hero {
  display: flex;
  align-items: center;
  gap: 20px;
  background: linear-gradient(120deg, var(--wood-primary) 0%, var(--wood-accent) 100%);
  border-radius: 12px;
  padding: 24px 28px;
  color: #fff;
  margin-bottom: 14px;
}

.shop-hero__logo {
  width: 76px;
  height: 76px;
  border-radius: 50%;
  object-fit: cover;
  border: 3px solid rgba(255, 255, 255, 0.65);
  background: #fff;
  flex-shrink: 0;
}

.shop-hero__logo--empty {
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 26px;
  font-family: Georgia, 'STSong', 'SimSun', serif;
  color: var(--wood-primary-dark);
}

.shop-hero__info {
  flex: 1;
  min-width: 0;
}

.shop-hero__name {
  margin: 0 0 6px;
  font-size: 22px;
  color: #fff;
}

.shop-hero__rest {
  margin-left: 8px;
  font-size: 12px;
  font-weight: normal;
  border: 1px solid rgba(255, 255, 255, 0.8);
  border-radius: 999px;
  padding: 2px 8px;
}

.shop-hero__intro {
  margin: 0 0 6px;
  font-size: 13px;
  opacity: 0.92;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.shop-hero__count {
  font-size: 12px;
  opacity: 0.85;
}

.shop-hero__back {
  flex-shrink: 0;
  height: 34px;
  line-height: 34px;
  padding: 0 16px;
  border-radius: 8px;
  border: 1px solid rgba(255, 255, 255, 0.8);
  color: #fff;
  text-decoration: none;
  font-size: 13px;
}

.shop-hero__back:hover {
  background: rgba(255, 255, 255, 0.16);
}

.cat-strip {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  padding: 12px 16px;
  background: var(--wood-surface);
  border: 1px solid var(--wood-border);
  border-radius: 10px;
  margin-bottom: 14px;
}

.cat-strip__item {
  height: 30px;
  padding: 0 14px;
  border: 1px solid transparent;
  border-radius: 999px;
  background: var(--wood-bg);
  color: var(--wood-text);
  font-size: 13px;
  cursor: pointer;
}

.cat-strip__item:hover {
  color: var(--wood-primary-dark);
}

.cat-strip__item--active {
  background: var(--wood-primary);
  border-color: var(--wood-primary);
  color: #fff;
}

.list-head {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  margin-bottom: 10px;
}

.list-head h3 {
  margin: 0;
  font-size: 17px;
}

.list-head__total {
  font-size: 12px;
  color: var(--wood-accent);
}

.sort-bar {
  display: flex;
  align-items: center;
  gap: 8px;
  background: var(--wood-surface);
  border: 1px solid var(--wood-border);
  border-radius: 10px;
  padding: 8px 12px;
  margin-bottom: 14px;
}

.sort-bar__tab {
  height: 30px;
  padding: 0 14px;
  border: 1px solid var(--wood-border);
  border-radius: 6px;
  background: #fff;
  color: var(--wood-text);
  font-size: 13px;
  cursor: pointer;
}

.sort-bar__tab--active {
  border-color: var(--wood-primary);
  background: #fdf1e7;
  color: var(--wood-primary-dark);
  font-weight: bold;
}

.placeholder {
  padding: 48px 0;
  text-align: center;
  color: var(--wood-accent);
  font-size: 14px;
  background: var(--wood-surface);
  border: 1px dashed var(--wood-border);
  border-radius: 10px;
}

.product-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(210px, 1fr));
  gap: 14px;
}

.product-card {
  background: var(--wood-surface);
  border: 1px solid var(--wood-border);
  border-radius: 10px;
  overflow: hidden;
  text-decoration: none;
  color: var(--wood-text);
  transition: box-shadow 0.2s, transform 0.2s;
}

.product-card:hover {
  box-shadow: 0 8px 20px rgba(107, 66, 38, 0.12);
  transform: translateY(-2px);
}

.product-card__img {
  width: 100%;
  aspect-ratio: 1;
  object-fit: cover;
  background: var(--wood-bg);
  display: block;
}

.product-card__name {
  padding: 10px 12px 4px;
  font-size: 14px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.product-card__meta {
  padding: 0 12px 12px;
  display: flex;
  justify-content: space-between;
  align-items: baseline;
}

.product-card__price {
  color: var(--wood-primary-dark);
  font-weight: bold;
  font-size: 15px;
}

.product-card__sales {
  color: var(--wood-accent);
  font-size: 12px;
}

.pager {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 16px;
  margin-top: 24px;
}

.pager__btn {
  height: 34px;
  padding: 0 18px;
  border: 1px solid var(--wood-border);
  border-radius: 8px;
  background: var(--wood-surface);
  color: var(--wood-primary-dark);
  font-size: 13px;
  cursor: pointer;
}

.pager__btn:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

.pager__info {
  font-size: 13px;
  color: var(--wood-accent);
}

.alert {
  margin-bottom: 16px;
  padding: 10px 12px;
  border-radius: 8px;
  background: #fdf1e7;
  border: 1px solid var(--wood-accent);
  color: var(--wood-primary-dark);
  font-size: 13px;
}
</style>
