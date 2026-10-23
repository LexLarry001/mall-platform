<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import BuyerHeader from '../components/BuyerHeader.vue'
import { listAddresses } from '../api/address'
import type { Address } from '../api/address'
import { getSettle, submitOrder } from '../api/order'
import type { SettleData } from '../api/order'
import { groupByShop } from '../utils/cart-group'

const router = useRouter()

const settle = ref<SettleData | null>(null)
const addresses = ref<Address[]>([])
const selectedAddressId = ref<string>('')
const loading = ref(true)
const submitting = ref(false)
const errorMsg = ref('')

const groups = computed(() => (settle.value ? groupByShop(settle.value.items) : []))
const itemCount = computed(() => settle.value?.items.reduce((s, i) => s + i.quantity, 0) ?? 0)

async function load(): Promise<void> {
  loading.value = true
  errorMsg.value = ''
  try {
    const [s, addrList] = await Promise.all([getSettle(), listAddresses()])
    settle.value = s
    addresses.value = addrList
    selectedAddressId.value =
      s.defaultAddress?.id ?? addrList.find((a) => a.isDefault === 1)?.id ?? addrList[0]?.id ?? ''
  } catch (e) {
    errorMsg.value = e instanceof Error ? e.message : '加载失败'
  } finally {
    loading.value = false
  }
}

async function onSubmit(): Promise<void> {
  if (!selectedAddressId.value) {
    alert('请选择收货地址')
    return
  }
  if (submitting.value) return
  submitting.value = true
  try {
    const orderNo = await submitOrder(selectedAddressId.value)
    await router.push({ name: 'orderDetail', params: { id: orderNo } })
  } catch (e) {
    alert(e instanceof Error ? e.message : '提交失败')
    submitting.value = false
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

function fullAddress(a: Address): string {
  return `${a.province}${a.city}${a.district}${a.detail}`
}

onMounted(load)
</script>

<template>
  <div class="checkout-page">
    <BuyerHeader />
    <main class="checkout-page__body">
      <h2 class="checkout-page__title">确认订单</h2>

      <div v-if="loading" class="placeholder">加载中…</div>
      <div v-else-if="errorMsg" class="placeholder">{{ errorMsg }}</div>

      <template v-else-if="settle">
        <section class="panel">
          <div class="panel__head">
            <h3>收货地址</h3>
            <RouterLink class="panel__action" :to="{ name: 'address' }">管理地址</RouterLink>
          </div>
          <div v-if="addresses.length === 0" class="panel__empty">
            还没有收货地址，请先
            <RouterLink class="panel__action" :to="{ name: 'address' }">新增地址</RouterLink>
          </div>
          <div v-else class="addr-list">
            <label
              v-for="a in addresses"
              :key="a.id"
              class="addr-card"
              :class="{ 'addr-card--active': selectedAddressId === a.id }"
            >
              <input v-model="selectedAddressId" type="radio" name="addr" :value="a.id" />
              <div class="addr-card__body">
                <div class="addr-card__line1">
                  <b>{{ a.receiver }}</b>
                  <span class="addr-card__phone">{{ a.phone }}</span>
                  <span v-if="a.isDefault === 1" class="addr-card__default">默认</span>
                </div>
                <div class="addr-card__line2">{{ fullAddress(a) }}</div>
              </div>
            </label>
          </div>
        </section>

        <section class="panel">
          <h3>商品清单</h3>
          <div v-for="g in groups" :key="g.shopId" class="order-shop">
            <div class="order-shop__name">{{ g.shopName }}</div>
            <div v-for="it in g.items" :key="it.skuId" class="order-item">
              <img class="order-item__img" :src="it.skuImg" :alt="it.productName" />
              <div class="order-item__info">
                <div class="order-item__name">{{ it.productName }}</div>
                <div class="order-item__spec">{{ specText(it.specJson) }}</div>
              </div>
              <div class="order-item__price">¥ {{ Number(it.price).toFixed(2) }}</div>
              <div class="order-item__qty">× {{ it.quantity }}</div>
              <div class="order-item__subtotal">¥ {{ (Number(it.price) * it.quantity).toFixed(2) }}</div>
            </div>
          </div>
        </section>

        <div class="checkout-footer">
          <div class="checkout-footer__right">
            <span class="checkout-footer__count">共 {{ itemCount }} 件</span>
            <span class="checkout-footer__total">
              应付金额：<b>¥ {{ Number(settle.totalAmount).toFixed(2) }}</b>
            </span>
            <button class="btn-submit" :disabled="submitting || addresses.length === 0" @click="onSubmit">
              {{ submitting ? '提交中…' : '提交订单' }}
            </button>
          </div>
        </div>
      </template>
    </main>
  </div>
</template>

<style scoped>
.checkout-page__body {
  max-width: 1000px;
  margin: 0 auto;
  padding: 20px 16px 40px;
}

.checkout-page__title {
  margin: 0 0 18px;
}

.placeholder {
  padding: 80px 0;
  text-align: center;
  color: var(--wood-accent);
}

.panel {
  background: var(--wood-surface);
  border: 1px solid var(--wood-border);
  border-radius: 10px;
  padding: 18px 20px;
  margin-bottom: 16px;
}

.panel__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.panel h3 {
  margin: 0 0 14px;
  font-size: 16px;
}

.panel__empty {
  color: var(--wood-accent);
  font-size: 14px;
}

.panel__action {
  color: var(--wood-primary);
  text-decoration: none;
  font-size: 13px;
}

.panel__action:hover {
  text-decoration: underline;
}

.addr-list {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(280px, 1fr));
  gap: 12px;
}

.addr-card {
  display: flex;
  gap: 10px;
  padding: 12px 14px;
  border: 1px solid var(--wood-border);
  border-radius: 10px;
  background: #fff;
  cursor: pointer;
  transition: border-color 0.2s;
}

.addr-card--active {
  border-color: var(--wood-primary);
  background: #fdf6ec;
}

.addr-card input {
  margin-top: 4px;
}

.addr-card__line1 {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 4px;
  color: var(--wood-text);
}

.addr-card__phone {
  color: var(--wood-accent);
  font-size: 13px;
}

.addr-card__default {
  font-size: 11px;
  color: var(--wood-primary-dark);
  border: 1px solid var(--wood-primary);
  border-radius: 999px;
  padding: 1px 8px;
}

.addr-card__line2 {
  color: var(--wood-accent);
  font-size: 13px;
}

.order-shop {
  border-top: 1px dashed var(--wood-border);
  padding-top: 10px;
  margin-top: 10px;
}

.order-shop:first-of-type {
  border-top: none;
  padding-top: 0;
  margin-top: 0;
}

.order-shop__name {
  font-weight: bold;
  color: var(--wood-primary-dark);
  font-size: 14px;
  margin-bottom: 8px;
}

.order-item {
  display: flex;
  align-items: center;
  gap: 14px;
  padding: 10px 0;
}

.order-item__img {
  width: 56px;
  height: 56px;
  object-fit: cover;
  border-radius: 8px;
  border: 1px solid var(--wood-border);
}

.order-item__info {
  flex: 1;
  min-width: 0;
}

.order-item__name {
  font-size: 14px;
  margin-bottom: 2px;
}

.order-item__spec {
  font-size: 12px;
  color: var(--wood-accent);
}

.order-item__price,
.order-item__qty {
  width: 90px;
  text-align: right;
  color: var(--wood-accent);
  font-size: 13px;
}

.order-item__subtotal {
  width: 110px;
  text-align: right;
  color: var(--wood-primary-dark);
  font-weight: bold;
}

.checkout-footer {
  position: sticky;
  bottom: 0;
  display: flex;
  justify-content: flex-end;
  padding: 14px 20px;
  background: var(--wood-surface);
  border: 1px solid var(--wood-border);
  border-radius: 10px;
  box-shadow: 0 -6px 18px rgba(107, 66, 38, 0.08);
}

.checkout-footer__right {
  display: flex;
  align-items: center;
  gap: 18px;
}

.checkout-footer__count {
  color: var(--wood-accent);
  font-size: 13px;
}

.checkout-footer__total b {
  color: var(--wood-primary-dark);
  font-size: 20px;
}

.btn-submit {
  height: 42px;
  padding: 0 34px;
  border: none;
  border-radius: 8px;
  background: var(--wood-primary);
  color: #fff;
  font-size: 15px;
  cursor: pointer;
}

.btn-submit:hover:not(:disabled) {
  background: var(--wood-primary-dark);
}

.btn-submit:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}
</style>
