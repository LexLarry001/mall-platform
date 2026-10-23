<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { getReviewSummary, listProductReviews } from '../api/review'
import type { ReviewItem, ReviewPage, ReviewSummary } from '../api/review'
import { formatDateTime } from '../utils/cart-group'

const props = defineProps<{ spuId: string }>()

const summary = ref<ReviewSummary | null>(null)
const page = ref<ReviewPage>({ records: [], total: 0, ratingStat: {} })
const loading = ref(true)
const summaryLoading = ref(true)

const ratingFilter = ref<number | null>(null)
const hasImage = ref(false)
const activeTag = ref('')
const pageNum = ref(1)
const PAGE_SIZE = 5

const totalPages = computed(() => Math.max(1, Math.ceil(page.value.total / PAGE_SIZE)))

/** 星级分布条宽度百分比（以总评价数为分母）。 */
function barWidth(star: number): string {
  const total = page.value.total || 1
  const count = Number(page.value.ratingStat[String(star)] ?? 0)
  return `${Math.round((count / total) * 100)}%`
}

function starCount(star: number): number {
  return Number(page.value.ratingStat[String(star)] ?? 0)
}

function initialOf(name: string): string {
  return name ? name.slice(0, 1) : '匿'
}

async function loadSummary(): Promise<void> {
  summaryLoading.value = true
  try {
    summary.value = await getReviewSummary(props.spuId)
  } catch {
    summary.value = null
  } finally {
    summaryLoading.value = false
  }
}

async function loadReviews(): Promise<void> {
  loading.value = true
  try {
    page.value = await listProductReviews(props.spuId, {
      pageNum: pageNum.value,
      pageSize: PAGE_SIZE,
      rating: ratingFilter.value ?? undefined,
      hasImage: hasImage.value || undefined,
      tag: activeTag.value || undefined,
    })
  } catch {
    page.value = { records: [], total: 0, ratingStat: {} }
  } finally {
    loading.value = false
  }
}

function onRating(rating: number): void {
  ratingFilter.value = ratingFilter.value === rating ? null : rating
  pageNum.value = 1
  void loadReviews()
}

function onImageOnly(): void {
  hasImage.value = !hasImage.value
  pageNum.value = 1
  void loadReviews()
}

function resetFilters(): void {
  ratingFilter.value = null
  hasImage.value = false
  activeTag.value = ''
  pageNum.value = 1
  void loadReviews()
}

function onTag(tag: string): void {
  activeTag.value = activeTag.value === tag ? '' : tag
  pageNum.value = 1
  void loadReviews()
}

function onPage(delta: number): void {
  const next = pageNum.value + delta
  if (next < 1 || next > totalPages.value) return
  pageNum.value = next
  void loadReviews()
}

watch(
  () => props.spuId,
  () => {
    pageNum.value = 1
    ratingFilter.value = null
    hasImage.value = false
    activeTag.value = ''
    void loadSummary()
    void loadReviews()
  },
)

onMounted(() => {
  void loadSummary()
  void loadReviews()
})
</script>

<template>
  <section class="reviews">
    <div class="reviews__head">
      <h3>买家评价</h3>
      <span v-if="page.total" class="reviews__total">共 {{ page.total }} 条</span>
    </div>

    <!-- 评分概览 + AI 摘要 -->
    <div class="overview">
      <div class="overview__score">
        <div class="overview__avg">{{ summary ? Number(summary.avgRating).toFixed(1) : '-' }}</div>
        <div class="overview__stars">
          <span v-for="n in 5" :key="n" :class="['star', { 'star--on': n <= Math.round(summary?.avgRating ?? 0) }]">★</span>
        </div>
        <div class="overview__rate">
          好评率 {{ summary ? Number(summary.recommendRate).toFixed(0) : '-' }}%
        </div>
      </div>

      <div class="overview__bars">
        <button
          v-for="star in [5, 4, 3, 2, 1]"
          :key="star"
          class="bar-row"
          :class="{ 'bar-row--active': ratingFilter === star }"
          @click="onRating(star)"
        >
          <span class="bar-row__label">{{ star }} 星</span>
          <span class="bar-row__track"><span class="bar-row__fill" :style="{ width: barWidth(star) }"></span></span>
          <span class="bar-row__count">{{ starCount(star) }}</span>
        </button>
      </div>

      <div class="overview__summary">
        <div class="summary-head">
          <span class="summary-head__title">AI 评价摘要</span>
          <span class="summary-head__source" :class="{ 'summary-head__source--ai': summary?.source === 'ai' }">
            {{ summary?.source === 'ai' ? 'DeepSeek 生成' : '评价数据统计' }}
          </span>
        </div>
        <div v-if="summaryLoading" class="summary-body">正在归纳评价…</div>
        <div v-else-if="!summary" class="summary-body">暂无评价摘要</div>
        <template v-else>
          <p class="summary-body">{{ summary.summary }}</p>
          <div v-if="summary.pros.length" class="summary-line">
            <span class="summary-line__tag summary-line__tag--pros">买家点赞</span>
            <span>{{ summary.pros.join(' · ') }}</span>
          </div>
          <div v-if="summary.cons.length" class="summary-line">
            <span class="summary-line__tag summary-line__tag--cons">待改进</span>
            <span>{{ summary.cons.join(' · ') }}</span>
          </div>
        </template>
      </div>
    </div>

    <!-- 筛选 -->
    <div class="filters">
      <button class="chip" :class="{ 'chip--active': !ratingFilter && !hasImage && !activeTag }" @click="resetFilters">
        全部
      </button>
      <button class="chip" :class="{ 'chip--active': hasImage }" @click="onImageOnly">有图</button>
      <button
        v-for="tag in summary?.tags ?? []"
        :key="tag"
        class="chip"
        :class="{ 'chip--active': activeTag === tag }"
        @click="onTag(tag)"
      >
        {{ tag }}
      </button>
    </div>

    <div v-if="loading" class="reviews__placeholder">加载评价中…</div>
    <div v-else-if="page.records.length === 0" class="reviews__placeholder">暂无符合条件的评价</div>
    <div v-else class="review-list">
      <article v-for="r in page.records" :key="r.id" class="review">
        <div class="review__avatar">{{ initialOf(r.userName) }}</div>
        <div class="review__main">
          <div class="review__head">
            <span class="review__user">{{ r.userName }}</span>
            <span class="review__stars">
              <span v-for="n in 5" :key="n" :class="['star', { 'star--on': n <= r.rating }]">★</span>
            </span>
            <span class="review__time">{{ formatDateTime(r.createTime) }}</span>
          </div>
          <p class="review__content">{{ r.content }}</p>
          <div v-if="r.images.length" class="review__images">
            <a v-for="img in r.images" :key="img" :href="img" target="_blank" rel="noopener">
              <img :src="img" alt="评价图片" loading="lazy" />
            </a>
          </div>
          <div v-if="r.tags.length" class="review__tags">
            <span v-for="t in r.tags" :key="t" class="review__tag">{{ t }}</span>
          </div>
          <div v-if="r.merchantReply" class="review__reply">
            <b>商家回复：</b>{{ r.merchantReply }}
          </div>
        </div>
      </article>
    </div>

    <div v-if="page.total > PAGE_SIZE" class="reviews__pager">
      <button :disabled="pageNum <= 1" @click="onPage(-1)">上一页</button>
      <span>{{ pageNum }} / {{ totalPages }}</span>
      <button :disabled="pageNum >= totalPages" @click="onPage(1)">下一页</button>
    </div>
  </section>
</template>

<style scoped>
.reviews {
  background: var(--wood-surface);
  border: 1px solid var(--wood-border);
  border-radius: 12px;
  padding: 22px 24px;
  margin-top: 20px;
}

.reviews__head {
  display: flex;
  align-items: baseline;
  gap: 10px;
  margin-bottom: 16px;
}

.reviews__head h3 {
  margin: 0;
}

.reviews__total {
  font-size: 12px;
  color: var(--wood-accent);
}

.overview {
  display: grid;
  grid-template-columns: 150px 1.1fr 1.6fr;
  gap: 22px;
  padding: 16px;
  background: #fdf8f1;
  border: 1px solid var(--wood-border);
  border-radius: 10px;
}

.overview__score {
  text-align: center;
  border-right: 1px dashed var(--wood-border);
  padding-right: 16px;
}

.overview__avg {
  font-size: 40px;
  font-weight: bold;
  color: var(--wood-primary-dark);
  line-height: 1.1;
}

.star {
  color: #ddd;
  font-size: 15px;
}

.star--on {
  color: #e8a33d;
}

.overview__stars {
  margin: 4px 0;
}

.overview__rate {
  font-size: 12px;
  color: var(--wood-accent);
}

.overview__bars {
  display: flex;
  flex-direction: column;
  gap: 4px;
  justify-content: center;
}

.bar-row {
  display: flex;
  align-items: center;
  gap: 8px;
  border: none;
  background: transparent;
  padding: 0;
  font-size: 12px;
  color: var(--wood-accent);
  cursor: pointer;
}

.bar-row--active {
  color: var(--wood-primary-dark);
  font-weight: bold;
}

.bar-row__label {
  width: 32px;
  text-align: left;
}

.bar-row__track {
  flex: 1;
  height: 7px;
  border-radius: 999px;
  background: #eee3d5;
  overflow: hidden;
}

.bar-row__fill {
  display: block;
  height: 100%;
  background: #e8a33d;
  border-radius: 999px;
}

.bar-row__count {
  width: 26px;
  text-align: right;
}

.overview__summary {
  min-width: 0;
}

.summary-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 6px;
}

.summary-head__title {
  font-size: 14px;
  font-weight: bold;
  color: var(--wood-primary-dark);
}

.summary-head__source {
  font-size: 11px;
  padding: 2px 8px;
  border-radius: 999px;
  background: #f0e6d8;
  color: #8a7a66;
}

.summary-head__source--ai {
  background: #fdeedd;
  color: #b5671f;
}

.summary-body {
  margin: 0;
  font-size: 13px;
  line-height: 1.7;
  color: var(--wood-text);
}

.summary-line {
  margin-top: 6px;
  font-size: 12px;
  color: var(--wood-accent);
  display: flex;
  gap: 6px;
}

.summary-line__tag {
  flex: none;
  padding: 1px 7px;
  border-radius: 4px;
  font-size: 11px;
}

.summary-line__tag--pros {
  background: #e8f3ea;
  color: #3d7a4a;
}

.summary-line__tag--cons {
  background: #f8e8e6;
  color: #b33;
}

.filters {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin: 16px 0 4px;
}

.chip {
  height: 28px;
  padding: 0 12px;
  border: 1px solid var(--wood-border);
  border-radius: 999px;
  background: #fff;
  color: var(--wood-text);
  font-size: 12px;
  cursor: pointer;
}

.chip--active {
  border-color: var(--wood-primary);
  background: #fdf1e7;
  color: var(--wood-primary-dark);
}

.reviews__placeholder {
  padding: 40px 0;
  text-align: center;
  color: var(--wood-accent);
  font-size: 13px;
}

.review-list {
  margin-top: 8px;
}

.review {
  display: flex;
  gap: 12px;
  padding: 16px 0;
  border-bottom: 1px dashed var(--wood-border);
}

.review:last-child {
  border-bottom: none;
}

.review__avatar {
  flex: none;
  width: 36px;
  height: 36px;
  border-radius: 50%;
  background: #f3e6d5;
  color: var(--wood-primary-dark);
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 14px;
}

.review__main {
  flex: 1;
  min-width: 0;
}

.review__head {
  display: flex;
  align-items: center;
  gap: 10px;
  font-size: 12px;
  color: var(--wood-accent);
}

.review__user {
  font-size: 13px;
  color: var(--wood-text);
}

.review__content {
  margin: 6px 0;
  font-size: 14px;
  line-height: 1.7;
  color: var(--wood-text);
  overflow-wrap: break-word;
}

.review__images {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
  margin-bottom: 6px;
}

.review__images img {
  width: 76px;
  height: 76px;
  object-fit: cover;
  border-radius: 8px;
  border: 1px solid var(--wood-border);
}

.review__tags {
  display: flex;
  gap: 6px;
  flex-wrap: wrap;
}

.review__tag {
  font-size: 11px;
  padding: 2px 8px;
  border-radius: 999px;
  background: #f3ede4;
  color: #8a7a66;
}

.review__reply {
  margin-top: 8px;
  padding: 8px 10px;
  border-radius: 8px;
  background: #fdf8f1;
  font-size: 12px;
  color: var(--wood-accent);
  line-height: 1.6;
}

.review__reply b {
  color: var(--wood-primary-dark);
}

.reviews__pager {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 14px;
  margin-top: 16px;
  font-size: 13px;
  color: var(--wood-accent);
}

.reviews__pager button {
  height: 30px;
  padding: 0 14px;
  border: 1px solid var(--wood-border);
  border-radius: 6px;
  background: #fff;
  color: var(--wood-primary-dark);
  font-size: 12px;
  cursor: pointer;
}

.reviews__pager button:disabled {
  opacity: 0.45;
  cursor: not-allowed;
}

@media (max-width: 860px) {
  .overview {
    grid-template-columns: 1fr;
  }

  .overview__score {
    border-right: none;
    border-bottom: 1px dashed var(--wood-border);
    padding: 0 0 10px;
  }
}
</style>
