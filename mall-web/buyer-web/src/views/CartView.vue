<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import BuyerHeader from '../components/BuyerHeader.vue'
import { listCart, removeCartItem, updateCartItem } from '../api/cart'
import type { CartItem } from '../api/cart'
import { allChecked, groupByShop, settleableItems, sumChecked } from '../utils/cart-group'

const router = useRouter()

const items = ref<CartItem[]>([])
const loading = ref(true)
const errorMsg = ref('')

const groups = computed(() => groupByShop(items.value))
const totalChecked = computed(() => sumChecked(items.value))
const checkedCount = computed(() => settleableItems(items.value).length)
const allSelected = computed(() => allChecked(items.value))
const hasInvalid = computed(() => items.value.some((i) => i.invalid))

async function load(): Promise<void> {
  loading.value = true
  errorMsg.value = ''
  try {
    items.value = await listCart()
  } catch (e) {
    errorMsg.value = e instanceof Error ? e.message : '加载失败'
  } finally {
    loading.value = false
  }
}

async function onToggleItem(item: CartItem): Promise<void> {
  if (item.invalid) return
  const next = !item.checked
  item.checked = next // 乐观更新
  try {
    await updateCartItem(item.skuId, { checked: next })
  } catch (e) {
    item.checked = !next // 回滚
    alert(e instanceof Error ? e.message : '操作失败')
  }
}

async function onToggleAll(): Promise<void> {
  const target = !allSelected.value
  const snapshot = items.value.map((i) => ({ ...i }))
  items.value.forEach((i) => {
    if (!i.invalid) i.checked = target
  })
  try {
    const valids = snapshot.filter((i) => !i.invalid)
    await Promise.all(valids.map((i) => updateCartItem(i.skuId, { checked: target })))
  } catch (e) {
    items.value = snapshot
    alert(e instanceof Error ? e.message : '操作失败')
  }
}

async function onChangeQty(item: CartItem, delta: number): Promise<void> {
  const next = item.quantity + delta
  if (next < 1) return
  const prev = item.quantity
  item.quantity = next
  try {
    await updateCartItem(item.skuId, { quantity: next })
  } catch (e) {
    item.quantity = prev
    alert(e instanceof Error ? e.message : '操作失败')
  }
}

async function onRemove(item: CartItem): Promise<void> {
  if (!confirm(`确定移除「${item.productName}」吗？`)) return
  try {
    await removeCartItem(item.skuId)
    items.value = items.value.filter((i) => i.skuId !== item.skuId)
  } catch (e) {
    alert(e instanceof Error ? e.message : '移除失败')
  }
}

function onCheckout(): void {
  if (checkedCount.value === 0) {
    alert('请先勾选要结算的商品')
    return
  }
  void router.push({ name: 'checkout' })
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

onMounted(load)
</script>

<template>
  <div class="cart-page">
    <BuyerHeader />
    <main class="cart-page__body">
      <h2 class="cart-page__title">我的购物车</h2>

      <div v-if="loading" class="placeholder">加载中…</div>
      <div v-else-if="errorMsg" class="placeholder">{{ errorMsg }}</div>
      <div v-else-if="items.length === 0" class="empty">
        <p>购物车还是空的，去逛逛吧</p>
        <RouterLink class="empty__link" :to="{ name: 'home' }">返回首页</RouterLink>
      </div>

      <template v-else>
        <div class="cart-toolbar">
          <label class="check-all">
            <input type="checkbox" :checked="allSelected" @change="onToggleAll" />
            全选
          </label>
          <span v-if="hasInvalid" class="cart-toolbar__tip">置灰商品已失效（下架/售罄），不参与结算</span>
        </div>

        <div v-for="g in groups" :key="g.shopId" class="shop-group">
          <div class="shop-group__head">
            <span class="shop-group__name">{{ g.shopName }}</span>
          </div>

          <div
            v-for="it in g.items"
            :key="it.skuId"
            class="cart-item"
            :class="{ 'cart-item--invalid': it.invalid }"
          >
            <input
              class="cart-item__check"
              type="checkbox"
              :checked="it.checked"
              :disabled="it.invalid"
              @change="onToggleItem(it)"
            />
            <RouterLink :to="{ name: 'productDetail', params: { id: it.spuId } }">
              <img class="cart-item__img" :src="it.skuImg" :alt="it.productName" />
            </RouterLink>
            <div class="cart-item__info">
              <div class="cart-item__name">
                {{ it.productName }}
                <span v-if="it.invalid" class="cart-item__badge">失效</span>
              </div>
              <div class="cart-item__spec">{{ specText(it.specJson) }}</div>
              <div class="cart-item__stock">库存 {{ it.stock }}</div>
            </div>
            <div class="cart-item__price">¥ {{ Number(it.price).toFixed(2) }}</div>
            <div class="cart-item__qty">
              <button class="qty-btn" :disabled="it.invalid || it.quantity <= 1" @click="onChangeQty(it, -1)">−</button>
              <span class="qty-num">{{ it.quantity }}</span>
              <button class="qty-btn" :disabled="it.invalid" @click="onChangeQty(it, 1)">＋</button>
            </div>
            <div class="cart-item__subtotal">
              ¥ {{ (Number(it.price) * it.quantity).toFixed(2) }}
            </div>
            <button class="cart-item__remove" @click="onRemove(it)">删除</button>
          </div>
        </div>

        <div class="cart-footer">
          <label class="check-all">
            <input type="checkbox" :checked="allSelected" @change="onToggleAll" />
            全选
          </label>
          <div class="cart-footer__right">
            <span class="cart-footer__count">已选 {{ checkedCount }} 件</span>
            <span class="cart-footer__total">合计：<b>¥ {{ totalChecked.toFixed(2) }}</b></span>
            <button class="btn-checkout" :disabled="checkedCount === 0" @click="onCheckout">
              去结算
            </button>
          </div>
        </div>
      </template>
    </main>
  </div>
</template>

<style scoped>
.cart-page__body {
  max-width: 1100px;
  margin: 0 auto;
  padding: 20px 16px 40px;
}

.cart-page__title {
  margin: 0 0 18px;
}

.placeholder {
  padding: 80px 0;
  text-align: center;
  color: var(--wood-accent);
}

.empty {
  text-align: center;
  padding: 80px 0;
  color: var(--wood-accent);
}

.empty__link {
  color: var(--wood-primary);
  text-decoration: none;
}

.cart-toolbar {
  display: flex;
  align-items: center;
  gap: 14px;
  padding: 10px 16px;
  background: var(--wood-surface);
  border: 1px solid var(--wood-border);
  border-radius: 10px;
  margin-bottom: 14px;
  font-size: 14px;
}

.cart-toolbar__tip {
  color: var(--wood-accent);
  font-size: 12px;
}

.check-all {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  cursor: pointer;
  user-select: none;
}

.shop-group {
  background: var(--wood-surface);
  border: 1px solid var(--wood-border);
  border-radius: 10px;
  margin-bottom: 14px;
  overflow: hidden;
}

.shop-group__head {
  padding: 10px 16px;
  border-bottom: 1px solid var(--wood-border);
  background: #faf4ea;
}

.shop-group__name {
  font-weight: bold;
  color: var(--wood-primary-dark);
  font-size: 14px;
}

.cart-item {
  display: flex;
  align-items: center;
  gap: 14px;
  padding: 14px 16px;
  border-bottom: 1px dashed var(--wood-border);
}

.cart-item:last-child {
  border-bottom: none;
}

.cart-item--invalid {
  opacity: 0.55;
}

.cart-item__img {
  width: 72px;
  height: 72px;
  object-fit: cover;
  border-radius: 8px;
  border: 1px solid var(--wood-border);
  display: block;
}

.cart-item__info {
  flex: 1;
  min-width: 0;
}

.cart-item__name {
  font-size: 14px;
  color: var(--wood-text);
  margin-bottom: 4px;
}

.cart-item__badge {
  margin-left: 6px;
  font-size: 11px;
  color: #b33;
  border: 1px solid #b33;
  border-radius: 999px;
  padding: 1px 6px;
}

.cart-item__spec {
  font-size: 12px;
  color: var(--wood-accent);
}

.cart-item__stock {
  font-size: 12px;
  color: var(--wood-accent);
}

.cart-item__price {
  width: 100px;
  text-align: right;
  color: var(--wood-primary-dark);
  font-weight: bold;
}

.cart-item__qty {
  display: flex;
  align-items: center;
  gap: 4px;
}

.qty-btn {
  width: 26px;
  height: 26px;
  border: 1px solid var(--wood-border);
  background: #fff;
  border-radius: 6px;
  cursor: pointer;
  font-size: 14px;
  color: var(--wood-primary-dark);
}

.qty-btn:disabled {
  opacity: 0.4;
  cursor: not-allowed;
}

.qty-num {
  min-width: 32px;
  text-align: center;
  font-size: 14px;
}

.cart-item__subtotal {
  width: 110px;
  text-align: right;
  color: var(--wood-primary-dark);
  font-weight: bold;
}

.cart-item__remove {
  border: none;
  background: none;
  color: var(--wood-accent);
  cursor: pointer;
  font-size: 13px;
}

.cart-item__remove:hover {
  color: #b33;
}

.cart-footer {
  position: sticky;
  bottom: 0;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  padding: 14px 16px;
  background: var(--wood-surface);
  border: 1px solid var(--wood-border);
  border-radius: 10px;
  box-shadow: 0 -6px 18px rgba(107, 66, 38, 0.08);
}

.cart-footer__right {
  display: flex;
  align-items: center;
  gap: 18px;
}

.cart-footer__count {
  color: var(--wood-accent);
  font-size: 13px;
}

.cart-footer__total b {
  color: var(--wood-primary-dark);
  font-size: 20px;
}

.btn-checkout {
  height: 42px;
  padding: 0 34px;
  border: none;
  border-radius: 8px;
  background: var(--wood-primary);
  color: #fff;
  font-size: 15px;
  cursor: pointer;
}

.btn-checkout:hover:not(:disabled) {
  background: var(--wood-primary-dark);
}

.btn-checkout:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}
</style>
