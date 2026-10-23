<script setup lang="ts">
import { ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { aiRecommend } from '../api/review'
import type { RecommendResult } from '../api/review'
import { useAuthStore } from '../stores/auth'

const auth = useAuthStore()
const route = useRoute()
const router = useRouter()

const open = ref(false)
const query = ref('')
const loading = ref(false)
const errorMsg = ref('')
const result = ref<RecommendResult | null>(null)

const QUICK_QUERIES = [
  '小户型客厅，想要不太软的沙发，预算 3000 以内',
  '卧室想要偏硬的实木床，预算 5000 以内',
  '实木餐桌，家里六口人，想要耐用一点',
  '书房想配一套书桌和书柜，预算 4000 以内',
]

async function onOpen(): Promise<void> {
  if (!auth.token) {
    await router.push({ name: 'login', query: { redirect: route.fullPath } })
    return
  }
  open.value = true
}

function close(): void {
  open.value = false
}

async function ask(text?: string): Promise<void> {
  const q = (text ?? query.value).trim()
  if (!q) {
    errorMsg.value = '请先描述一下您的需求'
    return
  }
  query.value = q
  errorMsg.value = ''
  loading.value = true
  try {
    result.value = await aiRecommend({ query: q, limit: 5 })
  } catch (e) {
    errorMsg.value = e instanceof Error ? e.message : '推荐失败，请稍后再试'
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <button class="ai-entry" title="AI 客服：按真实评价帮您挑商品" @click="onOpen">
    <span class="ai-entry__icon">✦</span>
    AI 客服
  </button>

  <Teleport to="body">
    <div v-if="open" class="ai-mask" @click.self="close">
      <aside class="ai-panel">
        <header class="ai-panel__head">
          <div>
            <h3>木集 AI 导购</h3>
            <p>根据真实买家评价，帮您挑到更合适的家具</p>
          </div>
          <button class="ai-panel__close" @click="close">×</button>
        </header>

        <div class="ai-panel__body">
          <label class="ai-label">说说您的需求</label>
          <textarea
            v-model="query"
            class="ai-input"
            maxlength="200"
            placeholder="例如：小户型客厅，想要不太软的沙发，预算 3000 以内"
            @keydown.ctrl.enter="ask()"
          ></textarea>
          <div class="ai-actions">
            <span class="ai-hint">Ctrl + Enter 发送</span>
            <button class="ai-send" :disabled="loading" @click="ask()">
              {{ loading ? '正在挑选…' : '帮我挑' }}
            </button>
          </div>

          <div class="ai-quick">
            <button v-for="q in QUICK_QUERIES" :key="q" class="ai-quick__item" :disabled="loading" @click="ask(q)">
              {{ q }}
            </button>
          </div>

          <div v-if="errorMsg" class="ai-error">{{ errorMsg }}</div>

          <div v-if="loading" class="ai-loading">
            <span class="ai-loading__dot"></span>
            正在阅读买家评价并筛选商品，请稍候…
          </div>

          <div v-else-if="result" class="ai-result">
            <div class="ai-result__meta">
              <span class="ai-source" :class="{ 'ai-source--ai': result.source === 'ai' }">
                {{ result.source === 'ai' ? 'AI 智能推荐' : '规则推荐（AI 未启用）' }}
              </span>
              <span v-if="result.parsed?.budgetMax" class="ai-parsed">
                预算 ≤ ¥{{ Number(result.parsed.budgetMax).toFixed(0) }}
              </span>
            </div>
            <p class="ai-result__summary">{{ result.summary }}</p>

            <div v-if="result.items.length === 0" class="ai-empty">
              没有找到匹配的商品，可以换个关键词或放宽预算再试。
            </div>
            <article v-for="item in result.items" :key="item.spuId" class="ai-item">
              <RouterLink :to="{ name: 'productDetail', params: { id: item.spuId } }" @click="close">
                <img :src="item.mainImg" :alt="item.name" class="ai-item__img" />
              </RouterLink>
              <div class="ai-item__main">
                <RouterLink :to="{ name: 'productDetail', params: { id: item.spuId } }" class="ai-item__name" @click="close">
                  {{ item.name }}
                </RouterLink>
                <div class="ai-item__meta">
                  <span class="ai-item__price">¥ {{ Number(item.price).toFixed(2) }}</span>
                  <span>★ {{ Number(item.avgRating).toFixed(1) }}（{{ item.commentCount }} 条评价）</span>
                </div>
                <p class="ai-item__reason">{{ item.reason }}</p>
                <blockquote v-for="(ev, idx) in item.evidence" :key="idx" class="ai-item__evidence">
                  “{{ ev }}”
                </blockquote>
              </div>
            </article>
          </div>
        </div>
      </aside>
    </div>
  </Teleport>
</template>

<style scoped>
.ai-entry {
  flex: none;
  height: 38px;
  padding: 0 14px;
  border: 1px solid var(--wood-primary);
  border-radius: 8px;
  background: linear-gradient(135deg, #fff8ef, #f7e6cf);
  color: var(--wood-primary-dark);
  font-size: 13px;
  letter-spacing: 0.04em;
  cursor: pointer;
  white-space: nowrap;
}

.ai-entry:hover {
  background: #f7e6cf;
}

.ai-entry__icon {
  margin-right: 4px;
  color: #c07a2c;
}

.ai-mask {
  position: fixed;
  inset: 0;
  background: rgba(40, 30, 20, 0.42);
  display: flex;
  justify-content: flex-end;
  z-index: 200;
}

.ai-panel {
  width: 460px;
  max-width: 100vw;
  height: 100%;
  background: var(--wood-surface);
  display: flex;
  flex-direction: column;
  box-shadow: -12px 0 40px rgba(60, 40, 20, 0.2);
}

.ai-panel__head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  padding: 18px 20px;
  border-bottom: 1px solid var(--wood-border);
  background: linear-gradient(135deg, #fdf1e3, #f8ead6);
}

.ai-panel__head h3 {
  margin: 0 0 4px;
  font-size: 17px;
  color: var(--wood-primary-dark);
}

.ai-panel__head p {
  margin: 0;
  font-size: 12px;
  color: var(--wood-accent);
}

.ai-panel__close {
  border: none;
  background: transparent;
  font-size: 22px;
  line-height: 1;
  color: var(--wood-accent);
  cursor: pointer;
}

.ai-panel__body {
  flex: 1;
  overflow-y: auto;
  padding: 16px 20px 28px;
}

.ai-label {
  display: block;
  font-size: 13px;
  color: var(--wood-primary-dark);
  margin-bottom: 6px;
}

.ai-input {
  width: 100%;
  box-sizing: border-box;
  min-height: 84px;
  resize: vertical;
  padding: 10px 12px;
  border: 1px solid var(--wood-border);
  border-radius: 10px;
  background: #fffdf9;
  font-family: inherit;
  font-size: 13px;
  color: var(--wood-text);
}

.ai-actions {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-top: 8px;
}

.ai-hint {
  font-size: 11px;
  color: var(--wood-accent);
}

.ai-send {
  height: 34px;
  padding: 0 18px;
  border: none;
  border-radius: 8px;
  background: var(--wood-primary);
  color: #fff;
  font-size: 13px;
  cursor: pointer;
}

.ai-send:disabled {
  opacity: 0.6;
  cursor: not-allowed;
}

.ai-quick {
  display: flex;
  flex-direction: column;
  gap: 6px;
  margin-top: 14px;
}

.ai-quick__item {
  text-align: left;
  padding: 7px 10px;
  border: 1px dashed var(--wood-border);
  border-radius: 8px;
  background: #fffdf9;
  color: var(--wood-accent);
  font-size: 12px;
  cursor: pointer;
}

.ai-quick__item:hover:not(:disabled) {
  border-color: var(--wood-primary);
  color: var(--wood-primary-dark);
}

.ai-error {
  margin-top: 12px;
  padding: 8px 10px;
  border-radius: 8px;
  background: #f8e8e6;
  color: #b33;
  font-size: 12px;
}

.ai-loading {
  margin-top: 16px;
  font-size: 13px;
  color: var(--wood-accent);
  display: flex;
  align-items: center;
  gap: 8px;
}

.ai-loading__dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: var(--wood-primary);
  animation: ai-pulse 1s infinite ease-in-out;
}

@keyframes ai-pulse {
  0%, 100% { opacity: 0.25; }
  50% { opacity: 1; }
}

.ai-result {
  margin-top: 18px;
  border-top: 1px solid var(--wood-border);
  padding-top: 14px;
}

.ai-result__meta {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 8px;
}

.ai-source {
  font-size: 11px;
  padding: 2px 8px;
  border-radius: 999px;
  background: #f0e6d8;
  color: #8a7a66;
}

.ai-source--ai {
  background: #fdeedd;
  color: #b5671f;
}

.ai-parsed {
  font-size: 11px;
  color: var(--wood-accent);
}

.ai-result__summary {
  margin: 0 0 12px;
  font-size: 13px;
  line-height: 1.7;
  color: var(--wood-text);
}

.ai-empty {
  padding: 20px 0;
  text-align: center;
  font-size: 13px;
  color: var(--wood-accent);
}

.ai-item {
  display: flex;
  gap: 10px;
  padding: 12px 0;
  border-top: 1px dashed var(--wood-border);
}

.ai-item__img {
  width: 76px;
  height: 76px;
  object-fit: cover;
  border-radius: 8px;
  border: 1px solid var(--wood-border);
  display: block;
}

.ai-item__main {
  flex: 1;
  min-width: 0;
}

.ai-item__name {
  font-size: 14px;
  color: var(--wood-text);
  text-decoration: none;
  font-weight: bold;
}

.ai-item__name:hover {
  color: var(--wood-primary-dark);
}

.ai-item__meta {
  display: flex;
  gap: 12px;
  margin: 4px 0 6px;
  font-size: 12px;
  color: var(--wood-accent);
}

.ai-item__price {
  color: var(--wood-primary-dark);
  font-weight: bold;
}

.ai-item__reason {
  margin: 0 0 6px;
  font-size: 12px;
  line-height: 1.6;
  color: var(--wood-text);
}

.ai-item__evidence {
  margin: 0 0 4px;
  padding: 6px 8px;
  border-left: 2px solid #e8a33d;
  background: #fdf8f1;
  font-size: 11px;
  line-height: 1.5;
  color: var(--wood-accent);
}
</style>
