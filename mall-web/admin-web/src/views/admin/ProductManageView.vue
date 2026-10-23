<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import http from '../../api/http'

interface ManageProduct {
  id: number
  shopId: number
  shopName: string
  name: string
  mainImg: string
  price: number
  sales: number
  status: number
  auditRemark: string | null
  updateTime: string
}

interface Page<T> {
  records: T[]
  total: number
}

const STATUS_TEXT: Record<number, string> = {
  0: '草稿',
  1: '待审核',
  2: '已上架',
  3: '审核驳回',
  4: '商家下架',
  5: '平台下架',
}
const STATUS_TAG: Record<number, string> = {
  0: 'info',
  1: 'warning',
  2: 'success',
  3: 'danger',
  4: 'info',
  5: 'danger',
}

const loading = ref(false)
const list = ref<ManageProduct[]>([])
const total = ref(0)
const query = reactive({ status: null as number | null, keyword: '', page: 1, size: 10 })

async function load(): Promise<void> {
  loading.value = true
  try {
    const params: Record<string, unknown> = { pageNum: query.page, pageSize: query.size }
    if (query.status !== null) params.status = query.status
    if (query.keyword.trim()) params.keyword = query.keyword.trim()
    const res = await http.get('/admin/product/list', { params })
    const page = res.data.data as Page<ManageProduct>
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

async function onViolationOff(row: ManageProduct): Promise<void> {
  let reason = ''
  try {
    const result = await ElMessageBox.prompt('请输入违规原因', `违规下架「${row.name}」`, {
      type: 'warning',
      inputValidator: (v) => (v && v.trim() ? true : '违规原因不能为空'),
    })
    reason = result.value.trim()
  } catch {
    return
  }
  try {
    await http.post(`/admin/product/${row.id}/violation-off`, null, { params: { reason } })
    ElMessage.success('已违规下架')
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
      <template #header><span>商品管控（全平台商品）</span></template>

      <div class="toolbar">
        <el-select v-model="query.status" placeholder="全部状态" clearable style="width: 140px" @change="onSearch">
          <el-option v-for="(text, value) in STATUS_TEXT" :key="value" :label="text" :value="Number(value)" />
        </el-select>
        <el-input v-model="query.keyword" placeholder="商品名称关键词" style="width: 240px" clearable @keyup.enter="onSearch" />
        <el-button type="primary" plain @click="onSearch">查询</el-button>
      </div>

      <el-table v-loading="loading" :data="list" style="width: 100%">
        <el-table-column label="商品" min-width="220">
          <template #default="{ row }">
            <div class="prod">
              <img :src="row.mainImg" alt="" class="prod__img" />
              <span>{{ row.name }}</span>
            </div>
          </template>
        </el-table-column>
        <el-table-column prop="shopName" label="店铺" min-width="120" />
        <el-table-column label="价格" width="110">
          <template #default="{ row }">¥ {{ Number(row.price).toFixed(2) }}</template>
        </el-table-column>
        <el-table-column prop="sales" label="销量" width="80" />
        <el-table-column label="状态" width="110">
          <template #default="{ row }">
            <el-tag :type="STATUS_TAG[row.status] || 'info'">{{ STATUS_TEXT[row.status] || row.status }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="auditRemark" label="备注" min-width="130">
          <template #default="{ row }">{{ row.auditRemark || '-' }}</template>
        </el-table-column>
        <el-table-column label="操作" width="150" fixed="right">
          <template #default="{ row }">
            <el-button
              v-if="[2, 4].includes(row.status)"
              size="small"
              type="danger"
              @click="onViolationOff(row)"
            >违规下架</el-button>
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
.prod {
  display: flex;
  align-items: center;
  gap: 10px;
}
.prod__img {
  width: 48px;
  height: 48px;
  object-fit: cover;
  border-radius: 6px;
  border: 1px solid #e5d5c0;
}
</style>
