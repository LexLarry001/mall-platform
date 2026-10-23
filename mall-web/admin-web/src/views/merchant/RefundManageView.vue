<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  approveRefund,
  listMerchantRefunds,
  REFUND_STATUS,
  rejectRefund,
  retryRefund,
} from '../../api/refund'
import type { RefundOrderView } from '../../api/refund'

const loading = ref(false)
const list = ref<RefundOrderView[]>([])
const statusFilter = ref<number | null>(null)

const STATUS_TAG: Record<number, string> = {
  0: 'warning',
  2: 'success',
  3: 'info',
  4: 'danger',
}

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

async function load(): Promise<void> {
  loading.value = true
  try {
    list.value = await listMerchantRefunds(statusFilter.value ?? undefined)
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '加载失败')
  } finally {
    loading.value = false
  }
}

/** 同意退款：同步调用支付宝沙箱，成功后库存自动回补（未发货）。 */
async function onApprove(row: RefundOrderView): Promise<void> {
  try {
    await ElMessageBox.confirm(
      `确认同意退款 ¥${Number(row.refundAmount).toFixed(2)}？将立即调用支付宝沙箱退款。`,
      '同意退款',
      { confirmButtonText: '同意并退款', cancelButtonText: '取消', type: 'warning' },
    )
  } catch {
    return
  }
  try {
    await approveRefund(row.refundNo)
    ElMessage.success('退款成功')
    await load()
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '退款失败')
    await load()
  }
}

/** 驳回退款：必须填写理由，买家可在订单详情看到。 */
async function onReject(row: RefundOrderView): Promise<void> {
  let remark = ''
  try {
    const result = await ElMessageBox.prompt('请填写驳回理由（买家可见）', '驳回退款', {
      inputPlaceholder: '例如：凭证不清晰，请补充商品问题照片',
      inputValidator: (value: string) => (value && value.trim() ? true : '请填写驳回理由'),
      confirmButtonText: '确认驳回',
      cancelButtonText: '取消',
    })
    remark = result.value.trim()
  } catch {
    return
  }
  try {
    await rejectRefund(row.refundNo, remark)
    ElMessage.success('已驳回')
    await load()
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '驳回失败')
  }
}

/** 退款失败重试：同一退款单号重放，支付宝 out_request_no 幂等。 */
async function onRetry(row: RefundOrderView): Promise<void> {
  try {
    await ElMessageBox.confirm('确认重新发起支付宝退款吗？', '重试退款', {
      confirmButtonText: '重试',
      cancelButtonText: '取消',
      type: 'warning',
    })
  } catch {
    return
  }
  try {
    await retryRefund(row.refundNo)
    ElMessage.success('退款成功')
    await load()
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '退款失败')
    await load()
  }
}

onMounted(load)
</script>

<template>
  <div>
    <el-card>
      <template #header>
        <div class="card-header">
          <span>售后处理</span>
          <div class="card-header__actions">
            <el-select
              v-model="statusFilter"
              placeholder="全部状态"
              clearable
              style="width: 140px"
              @change="load"
            >
              <el-option label="待审核" :value="0" />
              <el-option label="已退款" :value="2" />
              <el-option label="已驳回" :value="3" />
              <el-option label="退款失败" :value="4" />
            </el-select>
            <el-button @click="load">刷新</el-button>
          </div>
        </div>
      </template>

      <el-table v-loading="loading" :data="list" row-key="refundNo" empty-text="暂无退款申请">
        <el-table-column label="退款单" min-width="190">
          <template #default="{ row }">
            <div class="mono">{{ row.refundNo }}</div>
            <div class="muted">{{ formatDateTime(row.createTime) }}</div>
          </template>
        </el-table-column>

        <el-table-column label="商品" min-width="200">
          <template #default="{ row }">
            <div>{{ row.productName }}</div>
            <div class="muted">数量 × {{ row.quantity }}</div>
          </template>
        </el-table-column>

        <el-table-column label="关联订单" min-width="190">
          <template #default="{ row }">
            <div class="muted">主订单 {{ row.orderNo }}</div>
            <div class="mono">{{ row.subOrderNo }}</div>
          </template>
        </el-table-column>

        <el-table-column label="退款金额" width="110" align="right">
          <template #default="{ row }">¥ {{ Number(row.refundAmount).toFixed(2) }}</template>
        </el-table-column>

        <el-table-column label="退款原因" min-width="200">
          <template #default="{ row }">
            <div>{{ row.reason }}</div>
            <div v-if="row.auditRemark" class="muted">审核：{{ row.auditRemark }}</div>
            <div v-if="row.failMsg" class="fail">失败：{{ row.failMsg }}</div>
          </template>
        </el-table-column>

        <el-table-column label="凭证" width="130">
          <template #default="{ row }">
            <div v-if="row.evidenceImages?.length" class="evidence">
              <el-image
                v-for="(url, idx) in row.evidenceImages"
                :key="url + idx"
                :src="url"
                :preview-src-list="row.evidenceImages"
                :initial-index="idx"
                fit="cover"
                class="evidence__img"
                preview-teleported
              />
            </div>
            <span v-else class="muted">-</span>
          </template>
        </el-table-column>

        <el-table-column label="状态" width="100">
          <template #default="{ row }">
            <el-tag :type="STATUS_TAG[row.status] || 'info'">{{ REFUND_STATUS[row.status] ?? row.status }}</el-tag>
          </template>
        </el-table-column>

        <el-table-column label="操作" width="150" fixed="right">
          <template #default="{ row }">
            <el-button v-if="row.status === 0" type="primary" link @click="onApprove(row)">同意退款</el-button>
            <el-button v-if="row.status === 0" type="danger" link @click="onReject(row)">驳回</el-button>
            <el-button v-if="row.status === 4" type="warning" link @click="onRetry(row)">重试</el-button>
            <span v-if="row.status === 2 || row.status === 3" class="muted">已处理</span>
          </template>
        </el-table-column>
      </el-table>
    </el-card>
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

.fail {
  color: #b33;
  font-size: 12px;
}

.evidence {
  display: flex;
  gap: 6px;
}

.evidence__img {
  width: 36px;
  height: 36px;
  border-radius: 6px;
  border: 1px solid #e6ddd0;
}
</style>
