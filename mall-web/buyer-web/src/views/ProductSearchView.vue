<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import BuyerHeader from '../components/BuyerHeader.vue'
import { getCategoryTree } from '../api/category'
import type { CategoryNode } from '../api/category'
import { searchProducts } from '../api/product'
import type { PageResult, ProductItem, ProductSort } from '../api/product'

const route = useRoute()
const router = useRouter()

const categories = ref<CategoryNode[]>([])
const result = ref<PageResult<ProductItem>>({ records: [], total: 0, current: 1, size: 12 })
const loading = ref(false)
const errorMsg = ref('')

const keyword = ref('')
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

const isProductsPage = computed(() => route.name === 'products')
const totalPages = computed(() => Math.max(1, Math.ceil(result.value.total / result.value.size)))

const categoryName = computed(() => {
  if (!categoryId.value) return ''
  for (const l1 of categories.value) {
    if (String(l1.id) === categoryId.value) return l1.name
    for (const l2 of l1.children || []) {
      if (String(l2.id) === categoryId.value) return `${l1.name} / ${l2.name}`
    }
  }
  return ''
})

const pageTitle = computed(() => {
  if (keyword.value) return `“${keyword.value}” 的搜索结果`
  if (categoryName.value) return categoryName.value
  return '全部商品'
})

function syncFromRoute(): void {
  keyword.value = typeof route.query.keyword === 'string' ? route.query.keyword : ''
  categoryId.value = typeof route.query.categoryId === 'string' ? route.query.categoryId : ''
  page.value = 1
}

async function load(): Promise<void> {
  loading.value = true
  errorMsg.value = ''
  try {
    result.value = await searchProducts({
      keyword: keyword.value.trim() || undefined,
      categoryId: categoryId.value || undefined,
      sort: sort.value,
      page: page.value,
      size: 12,
    })
  } catch (e) {
    errorMsg.value = e instanceof Error ? e.message : '加载失败'
  } finally {
    loading.value = false
  }
}

/** 类目切换只保留关键词，不再提供页内关键词输入（搜索统一走顶部搜索框）。 */
function onCategory(id: string): void {
  const query: Record<string, string> = {}
  if (keyword.value.trim()) query.keyword = keyword.value.trim()
  if (id) query.categoryId = id
  void router.push({ name: isProductsPage.value ? 'products' : 'productSearch', query })
}

function onSort(s: ProductSort): void {
  sort.value = s
  page.value = 1
  void load()
}

function onPage(delta: number): void {
  const next = page.value + delta
  if (next < 1 || next > totalPages.value) return
  page.value = next
  void load()
}

watch(
  () => route.query,
  () => {
    syncFromRoute()
    void load()
  },
)

onMounted(async () => {
  syncFromRoute()
  void load()
  try {
    categories.value = await getCategoryTree()
  } catch {
    // 类目加载失败不阻塞商品列表
  }
})
</script>

<template>
  <div class="search-page">
    <BuyerHeader />
    <div class="search-page__body">
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
        <div class="list-head__title">
          <h2>{{ pageTitle }}</h2>
          <span v-if="categoryId" class="list-head__back" @click="onCategory('')">查看全部</span>
        </div>
        <span class="list-head__total">共 {{ result.total }} 件商品</span>
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
        没有找到相关商品，换一个关键词或类目试试
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
    </div>
  </div>
</template>

<style scoped>
.search-page {
  min-height: 100vh;
  background: var(--wood-bg);
}

.search-page__body {
  max-width: 1200px;
  margin: 0 auto;
  padding: 16px 16px 40px;
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
  gap: 12px;
  margin-bottom: 10px;
}

.list-head__title {
  display: flex;
  align-items: baseline;
  gap: 10px;
}

.list-head__title h2 {
  margin: 0;
  font-size: 20px;
}

.list-head__back {
  font-size: 13px;
  color: var(--wood-primary);
  cursor: pointer;
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
