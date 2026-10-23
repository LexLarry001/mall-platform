<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import BuyerHeader from '../components/BuyerHeader.vue'
import { queryPayResult } from '../api/pay'

/**
 * 支付结果页（M4，plan Task 4）：支付宝 return_url 同步跳回地址。
 *
 * 本地无公网收不到异步回调，挂载后主动 query 兜底推进订单（决策 1）：
 * status=1 支付成功；status=0 处理中（可手动重新查询）；异常/缺参展示失败态。
 */

const route = useRoute()
const orderNo = typeof route.query.out_trade_no === 'string' ? route.query.out_trade_no : ''

/** result: loading 查询中 / paid 支付成功 / pending 处理中 / error 异常。 */
const result = ref<'loading' | 'paid' | 'pending' | 'error'>(orderNo ? 'loading' : 'error')
const errorMsg = ref(orderNo ? '' : '缺少订单号参数，请在订单列表查看支付状态')

const MAX_AUTO_RETRY = 5
let attempts = 0
let timer: ReturnType<typeof setTimeout> | null = null

function clearTimer(): void {
  if (timer) {
    clearTimeout(timer)
    timer = null
  }
}

/** 支付宝查询可能有秒级延迟：pending 时每 2s 自动重试，最多 5 次。 */
async function doQuery(): Promise<void> {
  if (!orderNo) return
  clearTimer()
  try {
    const status = await queryPayResult(orderNo)
    if (status === 1) {
      result.value = 'paid'
      return
    }
    result.value = 'pending'
    if (attempts < MAX_AUTO_RETRY) {
      attempts += 1
      timer = setTimeout(() => void doQuery(), 2000)
    }
  } catch (e) {
    errorMsg.value = e instanceof Error ? e.message : '查询失败，请稍后重试'
    result.value = 'error'
  }
}

/** 手动重新查询：重置重试计数后立即再查一次。 */
async function query(): Promise<void> {
  if (!orderNo) return
  attempts = 0
  result.value = 'loading'
  errorMsg.value = ''
  await doQuery()
}

onMounted(() => {
  if (!orderNo) return
  void doQuery()
})

onBeforeUnmount(clearTimer)
</script>

<template>
  <div class="pr-page">
    <BuyerHeader />
    <main class="pr-page__body">
      <section class="pr-card">
        <template v-if="result === 'loading'">
          <div class="pr-icon pr-icon--loading">…</div>
          <h1 class="pr-title">正在确认支付结果</h1>
          <p class="pr-desc">请稍候，正在向支付宝查询交易状态…</p>
        </template>

        <template v-else-if="result === 'paid'">
          <div class="pr-icon pr-icon--success">✓</div>
          <h1 class="pr-title">支付成功</h1>
          <p class="pr-desc">订单 {{ orderNo }} 已完成支付，商家将尽快为您发货。</p>
          <div class="pr-actions">
            <RouterLink class="btn-primary" :to="{ name: 'orderDetail', params: { id: orderNo } }">
              查看订单
            </RouterLink>
            <RouterLink class="btn-ghost" :to="{ name: 'home' }">继续购物</RouterLink>
          </div>
        </template>

        <template v-else-if="result === 'pending'">
          <div class="pr-icon pr-icon--pending">!</div>
          <h1 class="pr-title">支付处理中</h1>
          <p class="pr-desc">
            暂未查询到订单 {{ orderNo }} 的付款成功记录。若您已在收银台完成付款，请稍等片刻后重新查询。
          </p>
          <div class="pr-actions">
            <button class="btn-primary" @click="query">重新查询</button>
            <RouterLink class="btn-ghost" :to="{ name: 'orderDetail', params: { id: orderNo } }">
              查看订单
            </RouterLink>
          </div>
        </template>

        <template v-else>
          <div class="pr-icon pr-icon--error">×</div>
          <h1 class="pr-title">支付结果确认失败</h1>
          <p class="pr-desc">{{ errorMsg }}</p>
          <div class="pr-actions">
            <button v-if="orderNo" class="btn-primary" @click="query">重新查询</button>
            <RouterLink class="btn-ghost" :to="{ name: 'orders' }">我的订单</RouterLink>
          </div>
        </template>
      </section>
    </main>
  </div>
</template>

<style scoped>
.pr-page {
  min-height: 100vh;
  background: var(--wood-bg, #f7f3ee);
}

.pr-page__body {
  max-width: 640px;
  margin: 0 auto;
  padding: 48px 16px;
}

.pr-card {
  background: var(--wood-surface, #fff);
  border: 1px solid var(--wood-border, #e5d9c8);
  border-radius: 12px;
  padding: 48px 32px;
  text-align: center;
}

.pr-icon {
  width: 64px;
  height: 64px;
  margin: 0 auto 16px;
  border-radius: 50%;
  font-size: 32px;
  line-height: 64px;
  font-weight: 700;
}

.pr-icon--loading {
  color: var(--wood-accent, #b5671f);
  border: 2px solid var(--wood-border, #e5d9c8);
}

.pr-icon--success {
  color: #fff;
  background: #4c9a2a;
}

.pr-icon--pending {
  color: #fff;
  background: var(--wood-accent, #b5671f);
}

.pr-icon--error {
  color: #fff;
  background: #c0392b;
}

.pr-title {
  margin: 0 0 8px;
  font-size: 22px;
  color: var(--wood-primary, #5b3a1e);
}

.pr-desc {
  margin: 0 0 24px;
  color: #7a6a58;
  font-size: 14px;
  line-height: 1.8;
  word-break: break-all;
}

.pr-actions {
  display: flex;
  gap: 12px;
  justify-content: center;
}

.btn-primary {
  display: inline-block;
  padding: 10px 28px;
  border: none;
  border-radius: 6px;
  background: var(--wood-accent, #b5671f);
  color: #fff;
  font-size: 14px;
  cursor: pointer;
  text-decoration: none;
}

.btn-primary:hover {
  background: var(--wood-primary-dark, #8f5218);
}

.btn-ghost {
  display: inline-block;
  padding: 10px 28px;
  border: 1px solid var(--wood-border, #e5d9c8);
  border-radius: 6px;
  background: #fff;
  color: var(--wood-primary, #5b3a1e);
  font-size: 14px;
  cursor: pointer;
  text-decoration: none;
}
</style>
