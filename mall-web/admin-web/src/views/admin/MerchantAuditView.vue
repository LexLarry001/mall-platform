<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import http from '../../api/http'

interface AuditItem {
  id: number
  username: string
  contactPhone: string | null
  auditStatus: number
  auditRemark: string | null
  status: number
  subjectName: string | null
  licenseNo: string | null
  licenseImg: string | null
  shopName: string | null
  shopLogo: string | null
  shopIntro: string | null
  createTime: string
  updateTime: string
}

interface Page<T> {
  records: T[]
  total: number
}

const AUDIT_TEXT: Record<number, string> = { 0: '待审核', 1: '已通过', 2: '已驳回' }
const AUDIT_TAG: Record<number, string> = { 0: 'warning', 1: 'success', 2: 'danger' }

const loading = ref(false)
const list = ref<AuditItem[]>([])
const total = ref(0)
const query = reactive({ keyword: '', page: 1, size: 10 })

async function load(): Promise<void> {
  loading.value = true
  try {
    const params: Record<string, unknown> = { pageNum: query.page, pageSize: query.size }
    if (query.keyword.trim()) params.keyword = query.keyword.trim()
    const res = await http.get('/admin/merchant/audit/list', { params })
    const page = res.data.data as Page<AuditItem>
    list.value = page.records
    total.value = page.total
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '加载失败')
  } finally {
    loading.value = false
  }
}

function onSearch(): void {
  query.page = 1
  load()
}

async function onPass(row: AuditItem): Promise<void> {
  try {
    await ElMessageBox.confirm(`确定通过「${row.shopName || row.username}」的入驻申请吗？将自动开店`, '审核确认', {
      type: 'warning',
    })
  } catch {
    return
  }
  try {
    await http.post(`/admin/merchant/audit/${row.id}/pass`)
    ElMessage.success('已通过并开店')
    load()
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '操作失败')
  }
}

async function onReject(row: AuditItem): Promise<void> {
  let remark = ''
  try {
    const result = await ElMessageBox.prompt('请输入驳回原因', `驳回「${row.shopName || row.username}」`, {
      type: 'warning',
      inputValidator: (v) => (v && v.trim() ? true : '驳回原因不能为空'),
    })
    remark = result.value.trim()
  } catch {
    return
  }
  try {
    await http.post(`/admin/merchant/audit/${row.id}/reject`, null, { params: { remark } })
    ElMessage.success('已驳回')
    load()
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '操作失败')
  }
}

async function onToggleStatus(row: AuditItem): Promise<void> {
  const target = row.status === 1 ? 0 : 1
  const action = target === 0 ? '冻结' : '启用'
  try {
    await ElMessageBox.confirm(`确定${action}商家「${row.username}」吗？`, `${action}确认`, { type: 'warning' })
  } catch {
    return
  }
  try {
    await http.post(`/admin/merchant/${row.id}/status`, null, { params: { status: target } })
    ElMessage.success(`已${action}`)
    load()
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '操作失败')
  }
}

onMounted(load)
</script>

<template>
  <div>
    <el-card>
      <template #header><span>商家入驻审核</span></template>

      <div class="toolbar">
        <el-input v-model="query.keyword" placeholder="账号/主体/店铺名" style="width: 240px" clearable @keyup.enter="onSearch" />
        <el-button type="primary" plain @click="onSearch">查询</el-button>
      </div>

      <el-table v-loading="loading" :data="list" style="width: 100%">
        <el-table-column prop="username" label="账号" width="120" />
        <el-table-column prop="subjectName" label="主体名称" min-width="150" />
        <el-table-column prop="shopName" label="申请店铺" min-width="130" />
        <el-table-column prop="licenseNo" label="证件号" min-width="150" />
        <el-table-column label="证件照" width="90">
          <template #default="{ row }">
            <el-image
              v-if="row.licenseImg"
              :src="row.licenseImg"
              :preview-src-list="[row.licenseImg]"
              fit="cover"
              style="width: 44px; height: 44px; border-radius: 6px"
              preview-teleported
            />
            <span v-else>-</span>
          </template>
        </el-table-column>
        <el-table-column label="审核状态" width="100">
          <template #default="{ row }">
            <el-tag :type="AUDIT_TAG[row.auditStatus] || 'info'">{{ AUDIT_TEXT[row.auditStatus] ?? row.auditStatus }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="账号状态" width="90">
          <template #default="{ row }">
            <el-tag :type="row.status === 1 ? 'success' : 'danger'">{{ row.status === 1 ? '正常' : '冻结' }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="auditRemark" label="审核备注" min-width="120">
          <template #default="{ row }">{{ row.auditRemark || '-' }}</template>
        </el-table-column>
        <el-table-column label="操作" width="240" fixed="right">
          <template #default="{ row }">
            <template v-if="row.auditStatus === 0">
              <el-button size="small" type="success" @click="onPass(row)">通过</el-button>
              <el-button size="small" type="danger" @click="onReject(row)">驳回</el-button>
            </template>
            <el-button
              v-if="row.auditStatus === 1"
              size="small"
              :type="row.status === 1 ? 'warning' : 'primary'"
              @click="onToggleStatus(row)"
            >
              {{ row.status === 1 ? '冻结' : '启用' }}
            </el-button>
          </template>
        </el-table-column>
      </el-table>

      <el-pagination
        v-model:current-page="query.page"
        :total="total"
        :page-size="query.size"
        layout="total, prev, pager, next"
        style="margin-top: 16px; justify-content: flex-end"
        @current-change="load"
      />
    </el-card>
  </div>
</template>

<style scoped>
.toolbar {
  display: flex;
  gap: 12px;
  margin-bottom: 16px;
}
</style>
