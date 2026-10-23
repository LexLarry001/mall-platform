<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { useRoute } from 'vue-router'
import BuyerHeader from '../components/BuyerHeader.vue'
import LogisticsMap from '../components/LogisticsMap.vue'
import { hasRoute } from '../utils/logisticsMap'
import { cancelOrder, getOrderDetail, ORDER_STATUS, SUB_ORDER_STATUS } from '../api/order'
import type { OrderDetail, OrderItem, SubOrder } from '../api/order'
import { createPayForm, queryPayResult, submitAlipayForm } from '../api/pay'
import { confirmReceive, getShipment, getTrace, LOGISTICS_STATUS } from '../api/logistics'
import type { LogisticsTrace, ShipmentInfo } from '../api/logistics'
import {
  applyRefund,
  listRefundsByOrder,
  REFUND_REASONS,
  REFUND_STATUS,
  uploadEvidence,
} from '../api/refund'
import type { RefundInfo } from '../api/refund'
import { submitReview, uploadReviewImage } from '../api/review'
import { formatDateTime } from '../utils/cart-group'

const route = useRoute()
const orderNo = typeof route.params.id === 'string' ? route.params.id : ''

const detail = ref<OrderDetail | null>(null)
const loading = ref(true)
const errorMsg = ref('')

const shipments = ref<Record<string, ShipmentInfo>>({})
const traces = ref<Record<string, LogisticsTrace[]>>({})
const confirming = ref('')
const refunds = ref<RefundInfo[]>([])

// 申请退款弹窗
const applyDialogVisible = ref(false)
const applying = ref(false)
const applyForm = reactive({
  orderNo: '',
  subOrderNo: '',
  orderItemId: 0 as number,
  productName: '',
  reason: '',
  note: '',
  evidenceImages: [] as string[],
})

const uploading = ref(false)

async function load(): Promise<void> {
  loading.value = true
  errorMsg.value = ''
  try {
    detail.value = await getOrderDetail(orderNo)
    await loadLogistics(detail.value)
    refunds.value = await listRefundsByOrder(orderNo)
  } catch (e) {
    errorMsg.value = e instanceof Error ? e.message : '订单不存在'
  } finally {
    loading.value = false
  }
}

/** 待收货/已完成/部分退款子订单加载发货单与轨迹（未发货/无权限静默忽略）。 */
async function loadLogistics(data: OrderDetail): Promise<void> {
  const targets = data.subOrders.filter((sub) => sub.status === 2 || sub.status === 3 || sub.status === 7)
  await Promise.all(
    targets.map(async (sub) => {
      const shipment = await getShipment(sub.subOrderNo)
      if (!shipment) return
      shipments.value[sub.subOrderNo] = shipment
      traces.value[sub.subOrderNo] = await getTrace(shipment.shipmentNo).catch(() => [])
    }),
  )
}

function shipmentOf(subOrderNo: string): ShipmentInfo | null {
  return shipments.value[subOrderNo] ?? null
}

function tracesOf(subOrderNo: string): LogisticsTrace[] {
  return traces.value[subOrderNo] ?? []
}

async function onConfirmReceive(subOrderNo: string): Promise<void> {
  if (!detail.value) return
  if (!confirm('确认已收到该店铺的商品吗？确认后订单将完成。')) return
  confirming.value = subOrderNo
  try {
    await confirmReceive(detail.value.orderNo, subOrderNo)
    await load()
  } catch (e) {
    alert(e instanceof Error ? e.message : '确认收货失败')
  } finally {
    confirming.value = ''
  }
}

async function onCancel(): Promise<void> {
  if (!detail.value) return
  if (!confirm(`确定取消订单 ${detail.value.orderNo} 吗？库存将自动回补。`)) return
  try {
    await cancelOrder(detail.value.orderNo)
    await load()
  } catch (e) {
    alert(e instanceof Error ? e.message : '取消失败')
  }
}

// ------------------------------------------------------------ 退款（M6）

/** 某订单项的最近一笔退款单。 */
function refundOf(itemId: number): RefundInfo | null {
  return refunds.value.find((r) => r.orderItemId === itemId) ?? null
}

/** 待审核 / 已退款属于"进行中或已终结"，不可再次申请。 */
function hasActiveRefund(itemId: number): boolean {
  const refund = refundOf(itemId)
  return !!refund && (refund.status === 0 || refund.status === 2)
}

/** 子订单处于待发货/待收货/部分退款，且该项无进行中退款时可申请。 */
function canApplyRefund(sub: SubOrder, item: OrderItem): boolean {
  return [1, 2, 7].includes(sub.status) && !hasActiveRefund(item.itemId)
}

function refundHint(item: OrderItem): string {
  const refund = refundOf(item.itemId)
  if (!refund) return ''
  if (refund.status === 3 && refund.auditRemark) return `已驳回：${refund.auditRemark}`
  if (refund.status === 4 && refund.failMsg) return `退款失败：${refund.failMsg}`
  return REFUND_STATUS[refund.status] ?? ''
}

function openApply(sub: SubOrder, item: OrderItem): void {
  if (!detail.value) return
  applyForm.orderNo = detail.value.orderNo
  applyForm.subOrderNo = sub.subOrderNo
  applyForm.orderItemId = item.itemId
  applyForm.productName = item.productName
  applyForm.reason = REFUND_REASONS[0]
  applyForm.note = ''
  applyForm.evidenceImages = []
  applyDialogVisible.value = true
}

function closeApply(): void {
  if (applying.value) return
  applyDialogVisible.value = false
}

async function onEvidencePick(event: Event): Promise<void> {
  const input = event.target as HTMLInputElement
  const files = Array.from(input.files ?? [])
  input.value = ''
  if (!files.length) return
  if (applyForm.evidenceImages.length + files.length > 3) {
    alert('凭证图片最多 3 张')
    return
  }
  uploading.value = true
  try {
    for (const file of files) {
      const url = await uploadEvidence(file)
      applyForm.evidenceImages.push(url)
    }
  } catch (e) {
    alert(e instanceof Error ? e.message : '凭证上传失败')
  } finally {
    uploading.value = false
  }
}

function removeEvidence(index: number): void {
  applyForm.evidenceImages.splice(index, 1)
}

async function submitApply(): Promise<void> {
  if (!applyForm.reason) {
    alert('请选择退款原因')
    return
  }
  const note = applyForm.note.trim()
  const reason = note
    ? applyForm.reason === '其它' ? `其它：${note}` : `${applyForm.reason}：${note}`
    : applyForm.reason
  applying.value = true
  try {
    await applyRefund({
      orderNo: applyForm.orderNo,
      subOrderNo: applyForm.subOrderNo,
      orderItemId: applyForm.orderItemId,
      reason,
      evidenceImages: [...applyForm.evidenceImages],
    })
    applyDialogVisible.value = false
    await load()
  } catch (e) {
    alert(e instanceof Error ? e.message : '退款申请失败')
  } finally {
    applying.value = false
  }
}

// ------------------------------------------------------------ 评价（M10）

const reviewDialogVisible = ref(false)
const submittingReview = ref(false)
const uploadingReview = ref(false)
const reviewForm = reactive({
  orderItemId: 0,
  productName: '',
  rating: 5,
  content: '',
  anonymous: false,
  images: [] as string[],
})

function canReview(sub: SubOrder, item: OrderItem): boolean {
  return sub.status === 3 && !item.reviewed
}

function openReview(item: OrderItem): void {
  reviewForm.orderItemId = item.itemId
  reviewForm.productName = item.productName
  reviewForm.rating = 5
  reviewForm.content = ''
  reviewForm.anonymous = false
  reviewForm.images = []
  reviewDialogVisible.value = true
}

function closeReview(): void {
  if (submittingReview.value) return
  reviewDialogVisible.value = false
}

async function onReviewImagePick(event: Event): Promise<void> {
  const input = event.target as HTMLInputElement
  const files = Array.from(input.files ?? [])
  input.value = ''
  if (!files.length) return
  if (reviewForm.images.length + files.length > 6) {
    alert('评价图片最多 6 张')
    return
  }
  uploadingReview.value = true
  try {
    for (const file of files) {
      const url = await uploadReviewImage(file)
      reviewForm.images.push(url)
    }
  } catch (e) {
    alert(e instanceof Error ? e.message : '图片上传失败')
  } finally {
    uploadingReview.value = false
  }
}

function removeReviewImage(index: number): void {
  reviewForm.images.splice(index, 1)
}

async function submitReviewForm(): Promise<void> {
  const content = reviewForm.content.trim()
  if (content.length < 5) {
    alert('评价内容至少 5 个字')
    return
  }
  if (submittingReview.value) return
  submittingReview.value = true
  try {
    await submitReview({
      orderItemId: reviewForm.orderItemId,
      rating: reviewForm.rating,
      content,
      images: [...reviewForm.images],
      anonymous: reviewForm.anonymous,
    })
    reviewDialogVisible.value = false
    await load()
  } catch (e) {
    alert(e instanceof Error ? e.message : '评价提交失败')
  } finally {
    submittingReview.value = false
  }
}

/** 手动/自动同步支付状态：本地收不到异步回调时，靠主动查询补齐。 */
const syncing = ref(false)
async function syncPayStatus(manual: boolean): Promise<void> {
  if (!detail.value || detail.value.status !== 0 || syncing.value) return
  syncing.value = true
  try {
    const status = await queryPayResult(detail.value.orderNo)
    if (status !== 0) {
      await load()
    } else if (manual) {
      alert('尚未查询到支付成功记录。若已在支付宝完成付款，请稍等 1 分钟后再试。')
    }
  } catch (e) {
    if (manual) alert(e instanceof Error ? e.message : '支付状态查询失败')
  } finally {
    syncing.value = false
  }
}

/** 去支付（M4）：取表单 HTML → 隐藏注入 + form.submit() 跳沙箱收银台。 */
const paying = ref(false)
async function onPay(): Promise<void> {
  if (!detail.value || paying.value) return
  paying.value = true
  try {
    const formHtml = await createPayForm(detail.value.orderNo)
    submitAlipayForm(formHtml)
  } catch (e) {
    alert(e instanceof Error ? e.message : '支付发起失败')
    paying.value = false
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

onMounted(async () => {
  await load()
  // 从收银台返回/直接打开详情时，待支付订单一律主动查询一次，避免"已付款仍显示未支付"
  if (detail.value?.status === 0) void syncPayStatus(false)
})
</script>

<template>
  <div class="od-page">
    <BuyerHeader />
    <main class="od-page__body">
      <div v-if="loading" class="placeholder">加载中…</div>
      <div v-else-if="errorMsg" class="placeholder">
        <p>{{ errorMsg }}</p>
        <RouterLink class="od-page__back" :to="{ name: 'orders' }">返回订单列表</RouterLink>
      </div>

      <template v-else-if="detail">
        <div class="od-head">
          <h2 class="od-head__title">订单详情</h2>
          <span class="od-head__status" :class="`od-head__status--${detail.status}`">
            {{ ORDER_STATUS[detail.status] ?? detail.status }}
          </span>
        </div>

        <section class="panel">
          <div class="od-meta">
            <div class="od-meta__row">
              <span class="od-meta__label">订单号</span>
              <span>{{ detail.orderNo }}</span>
            </div>
            <div class="od-meta__row">
              <span class="od-meta__label">下单时间</span>
              <span>{{ formatDateTime(detail.createTime) }}</span>
            </div>
            <div v-if="detail.status === 0" class="od-meta__row">
              <span class="od-meta__label">支付截止</span>
              <span class="od-meta__expire">{{ formatDateTime(detail.expireTime) }}（超时将自动取消）</span>
            </div>
          </div>
        </section>

        <section class="panel">
          <h3>收货信息</h3>
          <div class="od-addr">
            <b>{{ detail.receiver }}</b>
            <span class="od-addr__phone">{{ detail.phone }}</span>
            <div class="od-addr__full">{{ detail.fullAddress }}</div>
          </div>
        </section>

        <section v-for="sub in detail.subOrders" :key="sub.subOrderNo" class="panel">
          <div class="sub-head">
            <h3>{{ sub.shopName }}</h3>
            <span class="sub-head__status">{{ SUB_ORDER_STATUS[sub.status] ?? sub.status }}</span>
          </div>
          <div class="sub-head__no">子订单号：{{ sub.subOrderNo }}</div>
          <div v-for="it in sub.items" :key="it.itemId" class="order-row">
            <RouterLink :to="{ name: 'productDetail', params: { id: it.spuId } }">
              <img class="order-row__img" :src="it.mainImg" :alt="it.productName" />
            </RouterLink>
            <div class="order-row__info">
              <div class="order-row__name">{{ it.productName }}</div>
              <div class="order-row__spec">{{ specText(it.specJson) }}</div>
            </div>
            <div class="order-row__right">
              <div class="order-row__price">¥ {{ Number(it.price).toFixed(2) }} × {{ it.quantity }}</div>
              <div v-if="refundOf(it.itemId)" class="refund-tag" :class="`refund-tag--${refundOf(it.itemId)!.status}`">
                {{ refundHint(it) }}
              </div>
              <button v-if="canApplyRefund(sub, it)" class="btn-link" @click="openApply(sub, it)">申请退款</button>
              <button v-if="canReview(sub, it)" class="btn-link" @click="openReview(it)">去评价</button>
              <span v-else-if="sub.status === 3 && it.reviewed" class="reviewed-tag">已评价</span>
            </div>
          </div>
          <div class="sub-amount">子订单金额：<b>¥ {{ Number(sub.amount).toFixed(2) }}</b></div>

          <div v-if="shipmentOf(sub.subOrderNo)" class="logi">
            <div class="logi__head">
              <span class="logi__carrier">
                {{ shipmentOf(sub.subOrderNo)!.carrierName }} · {{ shipmentOf(sub.subOrderNo)!.trackingNo }}
              </span>
              <span class="logi__status">
                {{ LOGISTICS_STATUS[shipmentOf(sub.subOrderNo)!.currentStatus] ?? shipmentOf(sub.subOrderNo)!.currentStatus }}
              </span>
            </div>
            <div v-if="hasRoute(tracesOf(sub.subOrderNo))" class="logi__map-wrap">
              <LogisticsMap :traces="tracesOf(sub.subOrderNo)" :shipment="shipmentOf(sub.subOrderNo)" />
            </div>
            <div v-if="tracesOf(sub.subOrderNo).length" class="timeline">
              <div v-for="(t, idx) in tracesOf(sub.subOrderNo)" :key="t.id" class="timeline__item">
                <span class="timeline__dot" :class="{ 'timeline__dot--latest': idx === 0 }"></span>
                <div class="timeline__body">
                  <div class="timeline__desc">{{ t.desc }}</div>
                  <div class="timeline__time">
                    {{ formatDateTime(t.ts) }} · {{ LOGISTICS_STATUS[t.status] ?? t.status }}
                  </div>
                </div>
              </div>
            </div>
            <div v-else class="logi__empty">暂无物流轨迹</div>
            <div v-if="sub.status === 2 || sub.status === 7" class="logi__actions">
              <button class="btn-primary" :disabled="confirming === sub.subOrderNo" @click="onConfirmReceive(sub.subOrderNo)">
                {{ confirming === sub.subOrderNo ? '处理中…' : '确认收货' }}
              </button>
            </div>
          </div>
        </section>

        <section class="panel panel--summary">
          <div class="summary-row">
            <span>商品总额</span>
            <span>¥ {{ Number(detail.totalAmount).toFixed(2) }}</span>
          </div>
          <div class="summary-row">
            <span>运费</span>
            <span>¥ 0.00</span>
          </div>
          <div class="summary-row summary-row--total">
            <span>应付金额</span>
            <b>¥ {{ Number(detail.payAmount).toFixed(2) }}</b>
          </div>
          <div v-if="detail.status === 0" class="summary-actions">
            <button class="btn-primary" :disabled="paying" @click="onPay">
              {{ paying ? '跳转收银台…' : '去支付' }}
            </button>
            <button class="btn-ghost" :disabled="syncing" @click="syncPayStatus(true)">
              {{ syncing ? '查询中…' : '已支付？刷新状态' }}
            </button>
            <button class="btn-ghost btn-ghost--danger" @click="onCancel">取消订单</button>
          </div>
        </section>

        <div v-if="applyDialogVisible" class="modal-mask" @click.self="closeApply">
          <div class="modal">
            <h3 class="modal__title">申请退款</h3>
            <div class="modal__item">{{ applyForm.productName }}</div>

            <label class="modal__label">退款原因</label>
            <select v-model="applyForm.reason" class="modal__select">
              <option v-for="reason in REFUND_REASONS" :key="reason" :value="reason">{{ reason }}</option>
            </select>

            <label class="modal__label">补充说明</label>
            <textarea
              v-model="applyForm.note"
              class="modal__textarea"
              maxlength="120"
              placeholder="选填，将随退款原因一起提交给商家"
            ></textarea>

            <label class="modal__label">凭证图片（最多 3 张，选填）</label>
            <div class="evidence">
              <div v-for="(url, idx) in applyForm.evidenceImages" :key="url + idx" class="evidence__item">
                <img :src="url" alt="退款凭证" />
                <button class="evidence__remove" type="button" @click="removeEvidence(idx)">×</button>
              </div>
              <label v-if="applyForm.evidenceImages.length < 3" class="evidence__add">
                <input type="file" accept="image/*" multiple hidden :disabled="uploading" @change="onEvidencePick" />
                {{ uploading ? '上传中…' : '+' }}
              </label>
            </div>

            <div class="modal__actions">
              <button class="btn-ghost" @click="closeApply">取消</button>
              <button class="btn-primary" :disabled="applying || uploading" @click="submitApply">
                {{ applying ? '提交中…' : '提交申请' }}
              </button>
            </div>
          </div>
        </div>

        <div v-if="reviewDialogVisible" class="modal-mask" @click.self="closeReview">
          <div class="modal">
            <h3 class="modal__title">评价商品</h3>
            <div class="modal__item">{{ reviewForm.productName }}</div>

            <label class="modal__label">满意度</label>
            <div class="rating-picker">
              <button
                v-for="n in 5"
                :key="n"
                type="button"
                class="rating-picker__star"
                :class="{ 'rating-picker__star--on': n <= reviewForm.rating }"
                @click="reviewForm.rating = n"
              >
                ★
              </button>
              <span class="rating-picker__text">{{ reviewForm.rating }} 星</span>
            </div>

            <label class="modal__label">使用感受</label>
            <textarea
              v-model="reviewForm.content"
              class="modal__textarea"
              maxlength="500"
              placeholder="说说做工、尺寸、坐感/睡感或安装体验，至少 5 个字"
            ></textarea>

            <label class="modal__label">评价图片（最多 6 张，选填）</label>
            <div class="evidence">
              <div v-for="(url, idx) in reviewForm.images" :key="url + idx" class="evidence__item">
                <img :src="url" alt="评价图片" />
                <button class="evidence__remove" type="button" @click="removeReviewImage(idx)">×</button>
              </div>
              <label v-if="reviewForm.images.length < 6" class="evidence__add">
                <input type="file" accept="image/*" multiple hidden :disabled="uploadingReview" @change="onReviewImagePick" />
                {{ uploadingReview ? '上传中…' : '+' }}
              </label>
            </div>

            <label class="anonymous-row">
              <input v-model="reviewForm.anonymous" type="checkbox" />
              匿名评价（不展示我的用户名）
            </label>

            <div class="modal__actions">
              <button class="btn-ghost" @click="closeReview">取消</button>
              <button class="btn-primary" :disabled="submittingReview || uploadingReview" @click="submitReviewForm">
                {{ submittingReview ? '提交中…' : '发表评价' }}
              </button>
            </div>
          </div>
        </div>
      </template>
    </main>
  </div>
</template>

<style scoped>
.od-page__body {
  max-width: 900px;
  margin: 0 auto;
  padding: 20px 16px 40px;
}

.placeholder {
  padding: 80px 0;
  text-align: center;
  color: var(--wood-accent);
}

.od-page__back {
  color: var(--wood-primary);
  text-decoration: none;
}

.od-head {
  display: flex;
  align-items: center;
  gap: 16px;
  margin-bottom: 18px;
}

.od-head__title {
  margin: 0;
}

.od-head__status {
  font-size: 16px;
  font-weight: bold;
  color: var(--wood-primary-dark);
}

.od-head__status--0 {
  color: #b5671f;
}

.od-head__status--3 {
  color: #999;
}

.panel {
  background: var(--wood-surface);
  border: 1px solid var(--wood-border);
  border-radius: 10px;
  padding: 18px 20px;
  margin-bottom: 16px;
}

.panel h3 {
  margin: 0 0 12px;
  font-size: 16px;
}

.od-meta__row {
  display: flex;
  gap: 14px;
  padding: 6px 0;
  font-size: 14px;
}

.od-meta__label {
  width: 72px;
  color: var(--wood-accent);
}

.od-meta__expire {
  color: #b5671f;
}

.od-addr {
  font-size: 14px;
}

.od-addr__phone {
  margin-left: 12px;
  color: var(--wood-accent);
}

.od-addr__full {
  margin-top: 4px;
  color: var(--wood-accent);
}

.sub-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.sub-head__status {
  color: var(--wood-accent);
  font-size: 13px;
}

.sub-head__no {
  color: var(--wood-accent);
  font-size: 12px;
  margin-bottom: 10px;
}

.order-row {
  display: flex;
  align-items: center;
  gap: 14px;
  padding: 10px 0;
  border-bottom: 1px dashed var(--wood-border);
}

.order-row:last-of-type {
  border-bottom: none;
}

.order-row__img {
  width: 56px;
  height: 56px;
  object-fit: cover;
  border-radius: 8px;
  border: 1px solid var(--wood-border);
  display: block;
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

.sub-amount {
  text-align: right;
  padding-top: 10px;
  border-top: 1px dashed var(--wood-border);
  color: var(--wood-accent);
  font-size: 13px;
}

.sub-amount b {
  color: var(--wood-primary-dark);
  font-size: 16px;
}

.summary-row {
  display: flex;
  justify-content: space-between;
  padding: 6px 0;
  font-size: 14px;
  color: var(--wood-accent);
}

.summary-row--total {
  border-top: 1px solid var(--wood-border);
  margin-top: 6px;
  padding-top: 12px;
  font-size: 15px;
  color: var(--wood-text);
}

.summary-row--total b {
  color: var(--wood-primary-dark);
  font-size: 22px;
}

.summary-actions {
  display: flex;
  justify-content: flex-end;
  gap: 12px;
  margin-top: 16px;
}

.btn-primary {
  height: 40px;
  padding: 0 28px;
  border: none;
  border-radius: 8px;
  background: var(--wood-primary);
  color: #fff;
  font-size: 14px;
  cursor: pointer;
}

.btn-primary:disabled {
  opacity: 0.55;
  cursor: not-allowed;
}

.btn-ghost {
  height: 40px;
  padding: 0 20px;
  border-radius: 8px;
  font-size: 14px;
  cursor: pointer;
  border: 1px solid var(--wood-border);
  background: transparent;
  color: var(--wood-primary-dark);
}

.btn-ghost--danger {
  border-color: #b33;
  color: #b33;
}

.btn-ghost--danger:hover {
  background: #b33;
  color: #fff;
}

.logi {
  margin-top: 14px;
  padding-top: 14px;
  border-top: 1px dashed var(--wood-border);
}

.logi__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  font-size: 13px;
  margin-bottom: 10px;
}

.logi__carrier {
  color: var(--wood-text);
}

.logi__status {
  color: var(--wood-primary-dark);
  font-weight: bold;
}

.logi__empty {
  color: var(--wood-accent);
  font-size: 13px;
}

.timeline {
  margin: 0;
  padding: 0;
}

.timeline__item {
  position: relative;
  display: flex;
  gap: 10px;
  padding: 0 0 14px 0;
}

.timeline__item:not(:last-child)::before {
  content: '';
  position: absolute;
  left: 4px;
  top: 12px;
  bottom: 0;
  width: 1px;
  background: var(--wood-border);
}

.timeline__dot {
  flex: none;
  width: 9px;
  height: 9px;
  margin-top: 4px;
  border-radius: 50%;
  background: var(--wood-border);
}

.timeline__dot--latest {
  background: var(--wood-primary);
  box-shadow: 0 0 0 3px rgba(139, 94, 52, 0.15);
}

.timeline__desc {
  font-size: 13px;
  color: var(--wood-text);
}

.timeline__time {
  font-size: 12px;
  color: var(--wood-accent);
  margin-top: 2px;
}

.logi__actions {
  display: flex;
  justify-content: flex-end;
  margin-top: 12px;
}

/* ---------------------------------------------------------------- 退款 */

.order-row__right {
  display: flex;
  flex-direction: column;
  align-items: flex-end;
  gap: 6px;
  text-align: right;
}

.refund-tag {
  font-size: 12px;
  padding: 2px 8px;
  border-radius: 10px;
  background: #f3ede4;
  color: #8a7a66;
  max-width: 220px;
}

.refund-tag--0 {
  background: #fdf1e0;
  color: #b5671f;
}

.refund-tag--2 {
  background: #e8f3ea;
  color: #3d7a4a;
}

.refund-tag--3,
.refund-tag--4 {
  background: #f8e8e6;
  color: #b33;
}

.btn-link {
  border: none;
  background: none;
  padding: 0;
  color: var(--wood-primary);
  font-size: 13px;
  cursor: pointer;
  text-decoration: underline;
}

.modal-mask {
  position: fixed;
  inset: 0;
  background: rgba(40, 30, 20, 0.45);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 50;
}

.modal {
  width: 440px;
  max-width: calc(100vw - 32px);
  background: var(--wood-surface);
  border-radius: 12px;
  padding: 22px 24px;
  box-shadow: 0 12px 40px rgba(60, 40, 20, 0.25);
}

.modal__title {
  margin: 0 0 6px;
  font-size: 18px;
}

.modal__item {
  color: var(--wood-accent);
  font-size: 13px;
  margin-bottom: 12px;
}

.modal__label {
  display: block;
  font-size: 13px;
  color: var(--wood-text);
  margin: 12px 0 6px;
}

.modal__select,
.modal__textarea {
  width: 100%;
  box-sizing: border-box;
  border: 1px solid var(--wood-border);
  border-radius: 8px;
  padding: 9px 10px;
  font-size: 14px;
  font-family: inherit;
  background: #fffdf9;
  color: var(--wood-text);
}

.modal__textarea {
  min-height: 70px;
  resize: vertical;
}

.evidence {
  display: flex;
  gap: 10px;
  flex-wrap: wrap;
}

.evidence__item {
  position: relative;
  width: 64px;
  height: 64px;
  border-radius: 8px;
  overflow: hidden;
  border: 1px solid var(--wood-border);
}

.evidence__item img {
  width: 100%;
  height: 100%;
  object-fit: cover;
  display: block;
}

.evidence__remove {
  position: absolute;
  top: 2px;
  right: 2px;
  width: 18px;
  height: 18px;
  border: none;
  border-radius: 50%;
  background: rgba(0, 0, 0, 0.55);
  color: #fff;
  font-size: 12px;
  line-height: 1;
  cursor: pointer;
}

.evidence__add {
  width: 64px;
  height: 64px;
  border: 1px dashed var(--wood-border);
  border-radius: 8px;
  display: flex;
  align-items: center;
  justify-content: center;
  color: var(--wood-accent);
  font-size: 22px;
  cursor: pointer;
}

.modal__actions {
  display: flex;
  justify-content: flex-end;
  gap: 12px;
  margin-top: 22px;
}

.reviewed-tag {
  font-size: 12px;
  color: #3d7a4a;
  background: #e8f3ea;
  border-radius: 10px;
  padding: 2px 8px;
}

.rating-picker {
  display: flex;
  align-items: center;
  gap: 4px;
}

.rating-picker__star {
  border: none;
  background: transparent;
  font-size: 24px;
  line-height: 1;
  color: #ddd;
  cursor: pointer;
  padding: 0;
}

.rating-picker__star--on {
  color: #e8a33d;
}

.rating-picker__text {
  margin-left: 8px;
  font-size: 12px;
  color: var(--wood-accent);
}

.anonymous-row {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-top: 14px;
  font-size: 13px;
  color: var(--wood-accent);
  cursor: pointer;
}
</style>
