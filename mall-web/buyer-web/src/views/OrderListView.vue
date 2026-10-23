<script setup lang="ts">
import { onMounted, ref, watch } from 'vue'
import BuyerHeader from '../components/BuyerHeader.vue'
import { cancelOrder, listOrders, ORDER_STATUS } from '../api/order'
import type { OrderSummary } from '../api/order'
import { createPayForm, submitAlipayForm } from '../api/pay'
import { formatDateTime } from '../utils/cart-group'

const tabs = [
  { label: '全部', value: undefined as number | undefined },
  { label: '待支付', value: 0 },
  { label: '已支付', value: 1 },
  { label: '已完成', value: 2 },
  { label: '已取消', value: 3 },
]

const activeTab = ref<number | undefined>(undefined)
const orders = ref<OrderSummary[]>([])
const loading = ref(true)
const errorMsg = ref('')

async function load(): Promise<void> {
  loading.value = true
  errorMsg.value = ''
  try {
    orders.value = await listOrders(activeTab.value)
  } catch (e) {
    errorMsg.value = e instanceof Error ? e.message : '加载失败'
  } finally {
    loading.value = false
  }
}

function onTabChange(v: number | undefined): void {
  activeTab.value = v
}

async function onCancel(order: OrderSummary): Promise<void> {
  if (!confirm(`确定取消订单 ${order.orderNo} 吗？库存将自动回补。`)) return
  try {
    await cancelOrder(order.orderNo)
    await load()
  } catch (e) {
    alert(e instanceof Error ? e.message : '取消失败')
  }
}

/** 去支付（M4）：取表单 HTML → 隐藏注入 + form.submit() 跳沙箱收银台。 */
const paying = ref<string | null>(null)
async function onPay(order: OrderSummary): Promise<void> {
  if (paying.value) return
  paying.value = order.orderNo
  try {
    const formHtml = await createPayForm(order.orderNo)
    submitAlipayForm(formHtml)
  } catch (e) {
    alert(e instanceof Error ? e.message : '支付发起失败')
    paying.value = null
  }
}

function specText(json: string): string {
  try {
    const obj = JSON.parse(json) as Record<string, string>
    return Object.entries(obj)
      .map(([k, v]) => `${k}：${v}`)
      .join('　')
  } catch {
    return ''
  }
}

watch(activeTab, load)
onMounted(load)
</script>

<template>
  <div class="orders-page">
    <BuyerHeader />
    <main class="orders-page__body">
      <h2 class="orders-page__title">我的订单</h2>

      <div class="order-tabs">
        <button
          v-for="t in tabs"
          :key="t.label"
          class="order-tab"
          :class="{ 'order-tab--active': activeTab === t.value }"
          @click="onTabChange(t.value)"
        >
          {{ t.label }}
        </button>
      </div>

      <div v-if="loading" class="placeholder">加载中…</div>
      <div v-else-if="errorMsg" class="placeholder">{{ errorMsg }}</div>
      <div v-else-if="orders.length === 0" class="placeholder">暂无相关订单</div>

      <div v-else class="order-list">
        <div v-for="o in orders" :key="o.orderNo" class="order-card">
          <div class="order-card__head">
            <span class="order-card__time">{{ formatDateTime(o.createTime) }}</span>
            <span class="order-card__no">订单号：{{ o.orderNo }}</span>
            <span class="order-card__status" :class="`order-card__status--${o.status}`">
              {{ ORDER_STATUS[o.status] ?? o.status }}
            </span>
          </div>

          <div class="order-card__items">
            <div v-for="it in o.items" :key="it.skuId" class="order-row">
              <img class="order-row__img" :src="it.mainImg" :alt="it.productName" />
              <div class="order-row__info">
                <div class="order-row__name">{{ it.productName }}</div>
                <div class="order-row__spec">{{ specText(it.specJson) }}</div>
              </div>
              <div class="order-row__price">¥ {{ Number(it.price).toFixed(2) }} × {{ it.quantity }}</div>
            </div>
          </div>

          <div class="order-card__foot">
            <span class="order-card__amount">
              共 {{ o.items.reduce((s, i) => s + i.quantity, 0) }} 件，应付
              <b>¥ {{ Number(o.payAmount).toFixed(2) }}</b>
            </span>
            <div class="order-card__actions">
              <RouterLink class="btn-ghost" :to="{ name: 'orderDetail', params: { id: o.orderNo } }">
                订单详情
              </RouterLink>
              <button
                v-if="o.status === 0"
                class="btn-primary"
                :disabled="paying === o.orderNo"
                @click="onPay(o)"
              >
                {{ paying === o.orderNo ? '跳转收银台…' : '去支付' }}
              </button>
              <button v-if="o.status === 0" class="btn-ghost btn-ghost--danger" @click="onCancel(o)">
                取消订单
              </button>
            </div>
          </div>
        </div>
      </div>
    </main>
  </div>
</template>

<style scoped>
.orders-page__body {
  max-width: 1000px;
  margin: 0 auto;
  padding: 20px 16px 40px;
}

.orders-page__title {
  margin: 0 0 18px;
}

.order-tabs {
  display: flex;
  gap: 6px;
  margin-bottom: 16px;
  border-bottom: 1px solid var(--wood-border);
}

.order-tab {
  border: none;
  background: none;
  padding: 10px 18px;
  font-size: 14px;
  color: var(--wood-accent);
  cursor: pointer;
  border-bottom: 2px solid transparent;
}

.order-tab--active {
  color: var(--wood-primary-dark);
  border-bottom-color: var(--wood-primary);
  font-weight: bold;
}

.placeholder {
  padding: 80px 0;
  text-align: center;
  color: var(--wood-accent);
}

.order-card {
  background: var(--wood-surface);
  border: 1px solid var(--wood-border);
  border-radius: 10px;
  margin-bottom: 14px;
  overflow: hidden;
}

.order-card__head {
  display: flex;
  align-items: center;
  gap: 16px;
  padding: 10px 16px;
  background: #faf4ea;
  border-bottom: 1px solid var(--wood-border);
  font-size: 13px;
}

.order-card__time {
  color: var(--wood-accent);
}

.order-card__no {
  color: var(--wood-accent);
  flex: 1;
}

.order-card__status {
  font-weight: bold;
  color: var(--wood-primary-dark);
}

.order-card__status--0 {
  color: #b5671f;
}

.order-card__status--3 {
  color: #999;
  font-weight: normal;
}

.order-card__items {
  padding: 6px 16px;
}

.order-row {
  display: flex;
  align-items: center;
  gap: 14px;
  padding: 10px 0;
  border-bottom: 1px dashed var(--wood-border);
}

.order-row:last-child {
  border-bottom: none;
}

.order-row__img {
  width: 56px;
  height: 56px;
  object-fit: cover;
  border-radius: 8px;
  border: 1px solid var(--wood-border);
}

.order-row__info {
  flex: 1;
  min-width: 0;
}

.order-row__name {
  font-size: 14px;
  margin-bottom: 2px;
}

.order-row__spec {
  font-size: 12px;
  color: var(--wood-accent);
}

.order-row__price {
  color: var(--wood-accent);
  font-size: 13px;
}

.order-card__foot {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  padding: 12px 16px;
  border-top: 1px solid var(--wood-border);
}

.order-card__amount {
  color: var(--wood-accent);
  font-size: 13px;
}

.order-card__amount b {
  color: var(--wood-primary-dark);
  font-size: 18px;
}

.order-card__actions {
  display: flex;
  gap: 10px;
}

.btn-ghost,
.btn-primary {
  height: 34px;
  line-height: 34px;
  padding: 0 16px;
  border-radius: 8px;
  font-size: 13px;
  cursor: pointer;
  text-decoration: none;
  border: 1px solid var(--wood-primary);
  background: transparent;
  color: var(--wood-primary-dark);
}

.btn-primary {
  background: var(--wood-primary);
  color: #fff;
  border: none;
}

.btn-primary:disabled {
  opacity: 0.55;
  cursor: not-allowed;
}

.btn-ghost--danger {
  border-color: #b33;
  color: #b33;
}

.btn-ghost--danger:hover {
  background: #b33;
  color: #fff;
}
</style>
