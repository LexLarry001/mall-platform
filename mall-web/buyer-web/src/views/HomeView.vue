<script setup lang="ts">
import { onMounted, ref } from 'vue'
import BuyerHeader from '../components/BuyerHeader.vue'
import { getCategoryTree } from '../api/category'
import type { CategoryNode } from '../api/category'
import { searchProducts } from '../api/product'
import type { ProductItem } from '../api/product'

const categories = ref<CategoryNode[]>([])
const newProducts = ref<ProductItem[]>([])
const hotProducts = ref<ProductItem[]>([])
const loading = ref(true)

onMounted(async () => {
  try {
    const [tree, newest, hottest] = await Promise.all([
      getCategoryTree(),
      searchProducts({ sort: 'new', size: 10 }),
      searchProducts({ sort: 'sales', size: 10 }),
    ])
    categories.value = tree
    newProducts.value = newest.records
    hotProducts.value = hottest.records
  } finally {
    loading.value = false
  }
})
</script>

<template>
  <div class="home">
    <BuyerHeader />

    <!-- 顶部类目导航条（参考淘宝/京东：一级类目平铺，悬停展开二级） -->
    <nav class="cat-bar">
      <div class="cat-bar__inner">
        <RouterLink class="cat-bar__all" :to="{ name: 'products' }">全部商品</RouterLink>
        <div v-for="l1 in categories" :key="l1.id" class="cat-bar__item">
          <RouterLink
            class="cat-bar__l1"
            :to="{ name: 'products', query: { categoryId: l1.id } }"
          >
            {{ l1.name }}
          </RouterLink>
          <div v-if="l1.children && l1.children.length" class="cat-bar__dropdown">
            <RouterLink
              v-for="l2 in l1.children"
              :key="l2.id"
              class="cat-bar__l2"
              :to="{ name: 'products', query: { categoryId: l2.id } }"
            >
              {{ l2.name }}
            </RouterLink>
          </div>
        </div>
      </div>
    </nav>

    <main class="home__main">
      <section class="hero">
        <div class="hero__text">
          <h2>品质好物，一站购齐</h2>
          <p>多商家入驻 · 平台严选审核 · 放心选购</p>
        </div>
        <div class="hero__tags">
          <span>平台严选</span>
          <span>多店比价</span>
          <span>极速发货</span>
        </div>
      </section>

      <section class="product-section">
        <div class="section-head">
          <h3>新品上架</h3>
          <RouterLink class="section-head__more" :to="{ name: 'products' }">查看全部 ›</RouterLink>
        </div>
        <div v-if="loading" class="placeholder">加载中…</div>
        <div v-else-if="newProducts.length === 0" class="placeholder">暂无商品，敬请期待</div>
        <div v-else class="product-grid">
          <RouterLink
            v-for="p in newProducts"
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
      </section>

      <section class="product-section">
        <div class="section-head">
          <h3>热销榜单</h3>
          <RouterLink class="section-head__more" :to="{ name: 'products' }">查看全部 ›</RouterLink>
        </div>
        <div v-if="loading" class="placeholder">加载中…</div>
        <div v-else-if="hotProducts.length === 0" class="placeholder">暂无商品，敬请期待</div>
        <div v-else class="product-grid">
          <RouterLink
            v-for="p in hotProducts"
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
      </section>
    </main>
  </div>
</template>

<style scoped>
.home {
  min-height: 100vh;
}

/* ---- 顶部类目导航条 ---- */
.cat-bar {
  background: var(--wood-surface);
  border-bottom: 1px solid var(--wood-border);
  position: sticky;
  top: 66px;
  z-index: 90;
}

.cat-bar__inner {
  max-width: 1200px;
  margin: 0 auto;
  padding: 0 16px;
  display: flex;
  align-items: stretch;
  gap: 4px;
}

.cat-bar__all {
  display: flex;
  align-items: center;
  padding: 0 18px;
  font-size: 14px;
  font-weight: bold;
  color: #fff;
  background: var(--wood-primary);
  text-decoration: none;
}

.cat-bar__item {
  position: relative;
}

.cat-bar__l1 {
  display: flex;
  align-items: center;
  height: 42px;
  padding: 0 18px;
  font-size: 14px;
  color: var(--wood-text);
  text-decoration: none;
  white-space: nowrap;
}

.cat-bar__item:hover > .cat-bar__l1 {
  color: var(--wood-primary);
  background: var(--wood-bg);
}

.cat-bar__dropdown {
  display: none;
  position: absolute;
  top: 42px;
  left: 0;
  min-width: 140px;
  background: var(--wood-surface);
  border: 1px solid var(--wood-border);
  border-radius: 0 0 10px 10px;
  box-shadow: 0 10px 24px rgba(107, 66, 38, 0.14);
  padding: 8px;
  z-index: 100;
}

.cat-bar__item:hover .cat-bar__dropdown {
  display: block;
}

.cat-bar__l2 {
  display: block;
  padding: 8px 12px;
  font-size: 13px;
  color: var(--wood-text);
  text-decoration: none;
  border-radius: 6px;
  white-space: nowrap;
}

.cat-bar__l2:hover {
  color: var(--wood-primary);
  background: var(--wood-bg);
}

/* ---- 主内容 ---- */
.home__main {
  max-width: 1200px;
  margin: 0 auto;
  padding: 20px 16px 40px;
}

.hero {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 24px;
  background: linear-gradient(120deg, var(--wood-primary) 0%, var(--wood-accent) 100%);
  border-radius: 12px;
  padding: 30px 32px;
  color: #fff;
  margin-bottom: 20px;
}

.hero__tags {
  display: flex;
  gap: 10px;
  flex-wrap: wrap;
}

.hero__tags span {
  padding: 8px 16px;
  border-radius: 999px;
  border: 1px solid rgba(255, 255, 255, 0.55);
  background: rgba(255, 255, 255, 0.12);
  font-size: 13px;
  white-space: nowrap;
}

.hero h2 {
  margin: 0 0 8px;
  color: #fff;
  font-size: 28px;
}

.hero p {
  margin: 0;
  opacity: 0.9;
  font-size: 14px;
  letter-spacing: 0.1em;
}

.product-section {
  margin-bottom: 26px;
}

.section-head {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  margin-bottom: 12px;
}

.section-head h3 {
  margin: 0;
  font-size: 18px;
}

.section-head__more {
  font-size: 13px;
  color: var(--wood-primary);
  text-decoration: none;
}

.section-head__more:hover {
  text-decoration: underline;
}

.placeholder {
  padding: 40px 0;
  text-align: center;
  color: var(--wood-accent);
  font-size: 14px;
}

.product-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(200px, 1fr));
  gap: 14px;
}

.product-card {
  background: var(--wood-surface);
  border: 1px solid var(--wood-border);
  border-radius: 10px;
  overflow: hidden;
  text-decoration: none;
  color: var(--wood-text);
  transition: box-shadow 0.2s;
}

.product-card:hover {
  box-shadow: 0 8px 20px rgba(107, 66, 38, 0.12);
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
</style>
