<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import LogisticsMap from '../../components/LogisticsMap.vue'
import { hasRoute } from '../../utils/logisticsMap'
import {
  CARRIERS,
  LOGISTICS_NEXT,
  LOGISTICS_STATUS,
  SUB_ORDER_STATUS,
  getMerchantTrace,
  listMerchantOrders,
  pushLogistics,
  shipOrder,
} from '../../api/logistics'
import type { LogisticsTrace, MerchantOrder } from '../../api/logistics'

const loading = ref(false)
const list = ref<MerchantOrder[]>([])
const statusFilter = ref<number | null>(null)

const STATUS_TAG: Record<number, string> = {
  0: 'info',
  1: 'warning',
  2: 'primary',
  3: 'success',
  4: 'info',
  5: 'danger',
  6: 'info',
  7: 'info',
}

const shipDialogVisible = ref(false)
const shipSaving = ref(false)
const shipTarget = ref<MerchantOrder | null>(null)
const shipForm = reactive({ carrierCode: 'YTO', carrierName: '圆通', trackingNo: '', fromAddress: '' })

/** 记住上次发货地址，减少重复输入。 */
const FROM_ADDRESS_KEY = 'mall:lastFromAddress'

const traceDrawerVisible = ref(false)
const traceLoading = ref(false)
const traceList = ref<LogisticsTrace[]>([])
const traceOrder = ref<MerchantOrder | null>(null)

function formatDateTime(value: string | number[] | null | undefined): string {
  if (!value) return '-'
  if (typeof value === 'string') return value.replace('T', ' ').slice(0, 19)
  if (Array.isArray(value) && value.length >= 3) {
    const [y, m, d, hh = 0, mi = 0, ss = 0] = value
    const pad = (n: number) => String(n).padStart(2, '0')
    return `${y}-${pad(m)}-${pad(d)} ${pad(hh)}:${pad(mi)}:${pad(ss)}`
  }
  return '-'
}

function specText(json: string): string {
  try {
    return Object.entries(JSON.parse(json) as Record<string, string>)
      .map(([k, v]) => `${k}：${v}`)
      .join('　')
  } catch {
    return ''
  }
}

async function load(): Promise<void> {
  loading.value = true
  try {
    list.value = await listMerchantOrders(statusFilter.value ?? undefined)
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '加载失败')
  } finally {
    loading.value = false
  }
}

function onFilterChange(): void {
  load()
}

function openShip(row: MerchantOrder): void {
  shipTarget.value = row
  shipForm.carrierCode = 'YTO'
  shipForm.carrierName = '圆通'
  shipForm.trackingNo = ''
  shipForm.fromAddress = localStorage.getItem(FROM_ADDRESS_KEY) ?? ''
  shipDialogVisible.value = true
}

function onCarrierChange(code: string): void {
  const found = CARRIERS.find((c) => c.code === code)
  shipForm.carrierName = found?.name ?? ''
}

async function submitShip(): Promise<void> {
  if (!shipTarget.value) return
  if (!shipForm.trackingNo.trim()) {
    ElMessage.warning('请填写运单号')
    return
  }
  if (!shipForm.fromAddress.trim()) {
    ElMessage.warning('请填写发货地址')
    return
  }
  const fromAddress = shipForm.fromAddress.trim()
  shipSaving.value = true
  try {
    await shipOrder(shipTarget.value.subOrderNo, {
      carrierCode: shipForm.carrierCode,
      carrierName: shipForm.carrierName,
      trackingNo: shipForm.trackingNo.trim(),
      fromAddress,
    })
    localStorage.setItem(FROM_ADDRESS_KEY, fromAddress)
    ElMessage.success('发货成功，等待买家收货')
    shipDialogVisible.value = false
    await load()
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '发货失败')
  } finally {
    shipSaving.value = false
  }
}

async function onProgress(row: MerchantOrder): Promise<void> {
  if (!row.shipmentNo) return
  const next = LOGISTICS_NEXT[row.logisticsStatus ?? '']
  if (!next) return
  let desc = ''
  try {
    const result = await ElMessageBox.prompt(
      `将物流推进为「${LOGISTICS_STATUS[next]}」，可填写轨迹描述`,
      '推进物流',
      { inputPlaceholder: `例如：包裹已到达上海分拣中心`, confirmButtonText: '确认推进', cancelButtonText: '取消' },
    )
    desc = result.value?.trim() ?? ''
  } catch {
    return
  }
  try {
    await pushLogistics(row.shipmentNo, desc || `物流状态更新为${LOGISTICS_STATUS[next]}`)
    ElMessage.success(`已推进为${LOGISTICS_STATUS[next]}`)
    await load()
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '推进失败')
  }
}

async function openTrace(row: MerchantOrder): Promise<void> {
  if (!row.shipmentNo) return
  traceOrder.value = row
  traceDrawerVisible.value = true
  traceLoading.value = true
  traceList.value = []
  try {
    traceList.value = await getMerchantTrace(row.shipmentNo)
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '轨迹加载失败')
  } finally {
    traceLoading.value = false
  }
}

function nextLabel(row: MerchantOrder): string {
  const next = LOGISTICS_NEXT[row.logisticsStatus ?? '']
  return next ? LOGISTICS_STATUS[next] : ''
}

onMounted(load)
</script>

<template>
  <div>
    <el-card>
      <template #header>
        <div class="card-header">
          <span>订单管理</span>
          <div class="card-header__actions">
            <el-select v-model="statusFilter" placeholder="全部状态" clearable style="width: 140px" @change="onFilterChange">
              <el-option label="待发货" :value="1" />
              <el-option label="待收货" :value="2" />
              <el-option label="已完成" :value="3" />
              <el-option label="已取消" :value="4" />
              <el-option label="退款中" :value="5" />
              <el-option label="已退款" :value="6" />
              <el-option label="部分退款" :value="7" />
            </el-select>
            <el-button @click="load">刷新</el-button>
          </div>
        </div>
      </template>

      <el-table v-loading="loading" :data="list" row-key="subOrderNo" empty-text="暂无订单">
        <el-table-column label="子订单号" min-width="190">
          <template #default="{ row }">
            <div class="mono">{{ row.subOrderNo }}</div>
            <div class="muted">主订单 {{ row.orderNo || '-' }}</div>
            <div class="muted">{{ formatDateTime(row.createTime) }}</div>
          </template>
        </el-table-column>

        <el-table-column label="商品" min-width="240">
          <template #default="{ row }">
            <div v-for="it in row.items" :key="it.skuId" class="goods">
              <img class="goods__img" :src="it.mainImg" :alt="it.productName" />
              <div>
                <div>{{ it.productName }}</div>
                <div class="muted">{{ specText(it.specJson) }}</div>
                <div class="muted">¥ {{ Number(it.price).toFixed(2) }} × {{ it.quantity }}</div>
              </div>
            </div>
          </template>
        </el-table-column>

        <el-table-column label="收货信息" min-width="200">
          <template #default="{ row }">
            <div>{{ row.receiver || '-' }} {{ row.phone || '' }}</div>
            <div class="muted">{{ row.fullAddress || '-' }}</div>
          </template>
        </el-table-column>

        <el-table-column label="金额" width="110" align="right">
          <template #default="{ row }">¥ {{ Number(row.amount).toFixed(2) }}</template>
        </el-table-column>

        <el-table-column label="交易状态" width="100">
          <template #default="{ row }">
            <el-tag :type="STATUS_TAG[row.status] || 'info'">{{ SUB_ORDER_STATUS[row.status] ?? row.status }}</el-tag>
          </template>
        </el-table-column>

        <el-table-column label="物流" min-width="170">
          <template #default="{ row }">
            <template v-if="row.shipmentNo">
              <div>{{ row.carrierName }} · {{ row.trackingNo }}</div>
              <div class="muted">
                <el-tag size="small" :type="row.logisticsStatus === 'DELIVERED' ? 'success' : 'warning'">
                  {{ LOGISTICS_STATUS[row.logisticsStatus || ''] ?? row.logisticsStatus }}
                </el-tag>
                <span class="muted"> {{ row.traceCount || 0 }} 条轨迹</span>
              </div>
            </template>
            <span v-else class="muted">未发货</span>
          </template>
        </el-table-column>

        <el-table-column label="操作" width="150" fixed="right">
          <template #default="{ row }">
            <el-button v-if="row.status === 1 || row.status === 7" type="primary" link @click="openShip(row)">发货</el-button>
            <el-button
              v-if="row.shipmentNo && LOGISTICS_NEXT[row.logisticsStatus || '']"
              type="warning"
              link
              @click="onProgress(row)"
            >
              推进为{{ nextLabel(row) }}
            </el-button>
            <el-button v-if="row.shipmentNo" link @click="openTrace(row)">轨迹</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <el-dialog v-model="shipDialogVisible" title="发货" width="440px">
      <el-form label-width="80px">
        <el-form-item label="承运商">
          <el-select v-model="shipForm.carrierCode" style="width: 100%" @change="onCarrierChange">
            <el-option v-for="c in CARRIERS" :key="c.code" :label="c.name" :value="c.code" />
          </el-select>
        </el-form-item>
        <el-form-item label="运单号">
          <el-input v-model="shipForm.trackingNo" placeholder="请输入快递运单号" maxlength="64" />
        </el-form-item>
        <el-form-item label="发货地址">
          <el-input
            v-model="shipForm.fromAddress"
            type="textarea"
            :rows="2"
            placeholder="例如：上海市浦东新区世纪大道 100 号（用于地图路线规划）"
            maxlength="255"
            show-word-limit
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="shipDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="shipSaving" @click="submitShip">确认发货</el-button>
      </template>
    </el-dialog>

    <el-drawer v-model="traceDrawerVisible" title="物流轨迹" size="440px">
      <div v-if="traceOrder" class="trace-head">
        <div>{{ traceOrder.carrierName }} · {{ traceOrder.trackingNo }}</div>
        <div class="muted">发货单 {{ traceOrder.shipmentNo }}</div>
      </div>
      <div v-loading="traceLoading">
        <LogisticsMap
          v-if="hasRoute(traceList)"
          :traces="traceList"
          :shipment="{ currentStatus: traceOrder?.logisticsStatus ?? null }"
        />
        <el-empty v-if="!traceLoading && traceList.length === 0" description="暂无轨迹" />
        <el-timeline v-else>
          <el-timeline-item
            v-for="t in traceList"
            :key="t.id"
            :timestamp="formatDateTime(t.ts)"
            :type="t.status === 'DELIVERED' ? 'success' : 'primary'"
          >
            <div class="trace-title">{{ LOGISTICS_STATUS[t.status] ?? t.status }}</div>
            <div class="muted">{{ t.desc }}</div>
            <div v-if="t.address" class="muted">{{ t.address }}</div>
          </el-timeline-item>
        </el-timeline>
      </div>
    </el-drawer>
  </div>
</template>

<style scoped>
.card-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.card-header__actions {
  display: flex;
  gap: 12px;
}

.mono {
  font-family: Consolas, Monaco, monospace;
  font-size: 13px;
}

.muted {
  color: #8a7a66;
  font-size: 12px;
}

.goods {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 6px;
}

.goods__img {
  width: 48px;
  height: 48px;
  border-radius: 6px;
  object-fit: cover;
  border: 1px solid #e5dccf;
}

.trace-head {
  padding-bottom: 12px;
  margin-bottom: 16px;
  border-bottom: 1px dashed #e5dccf;
}

.trace-title {
  font-weight: 600;
}
</style>
