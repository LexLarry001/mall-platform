<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import http from '../../api/http'

interface ProductItem {
  spuId: number
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
  current: number
  size: number
}

const STATUS_TEXT: Record<number, string> = {
  0: '草稿',
  1: '待审核',
  2: '已上架',
  3: '审核驳回',
  4: '已下架',
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

const router = useRouter()
const loading = ref(false)
const list = ref<ProductItem[]>([])
const total = ref(0)
const query = reactive({ status: null as number | null, keyword: '', page: 1, size: 10 })

async function load(): Promise<void> {
  loading.value = true
  try {
    const params: Record<string, unknown> = { page: query.page, size: query.size }
    if (query.status !== null) params.status = query.status
    if (query.keyword.trim()) params.keyword = query.keyword.trim()
    const res = await http.get('/merchant/product/list', { params })
    const page = res.data.data as Page<ProductItem>
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

async function onOff(row: ProductItem): Promise<void> {
  try {
    await http.post(`/merchant/product/${row.spuId}/off`)
    ElMessage.success('已下架')
    load()
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '操作失败')
  }
}

async function onOn(row: ProductItem): Promise<void> {
  try {
    await http.post(`/merchant/product/${row.spuId}/on`)
    ElMessage.success('已上架')
    load()
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '操作失败')
  }
}

async function onDelete(row: ProductItem): Promise<void> {
  try {
    await ElMessageBox.confirm(`确定删除商品「${row.name}」吗？删除后不可恢复`, '删除确认', { type: 'warning' })
  } catch {
    return
  }
  try {
    await http.delete(`/merchant/product/${row.spuId}`)
    ElMessage.success('已删除')
    load()
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '删除失败')
  }
}

function onEdit(row: ProductItem): void {
  router.push({ name: 'mProductEdit', query: { spuId: row.spuId } })
}

onMounted(load)
</script>

<template>
  <div>
    <el-card>
      <template #header>
        <div class="card-header">
          <span>商品管理</span>
          <el-button type="primary" @click="router.push({ name: 'mProductEdit' })">发布商品</el-button>
        </div>
      </template>

      <div class="toolbar">
        <el-select v-model="query.status" placeholder="全部状态" clearable style="width: 140px" @change="onSearch">
          <el-option v-for="(text, value) in STATUS_TEXT" :key="value" :label="text" :value="Number(value)" />
        </el-select>
        <el-input
          v-model="query.keyword"
          placeholder="商品名称关键词"
          style="width: 220px"
          clearable
          @keyup.enter="onSearch"
        />
        <el-button type="primary" plain @click="onSearch">查询</el-button>
      </div>

      <el-table v-loading="loading" :data="list" style="width: 100%">
        <el-table-column label="商品" min-width="240">
          <template #default="{ row }">
            <div class="prod">
              <img :src="row.mainImg" alt="" class="prod__img" />
              <span>{{ row.name }}</span>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="价格" width="110">
          <template #default="{ row }">¥ {{ Number(row.price).toFixed(2) }}</template>
        </el-table-column>
        <el-table-column prop="sales" label="销量" width="80" />
        <el-table-column label="状态" width="110">
          <template #default="{ row }">
            <el-tag :type="STATUS_TAG[row.status] || 'info'">{{ STATUS_TEXT[row.status] || row.status }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="审核备注" min-width="140">
          <template #default="{ row }">
            <span v-if="row.auditRemark" class="remark">{{ row.auditRemark }}</span>
            <span v-else>-</span>
          </template>
        </el-table-column>
        <el-table-column prop="updateTime" label="更新时间" width="170" />
        <el-table-column label="操作" width="230" fixed="right">
          <template #default="{ row }">
            <el-button size="small" @click="onEdit(row)">编辑</el-button>
            <el-button v-if="row.status === 2" size="small" type="warning" @click="onOff(row)">下架</el-button>
            <el-button v-if="row.status === 4" size="small" type="success" @click="onOn(row)">上架</el-button>
            <el-button
              v-if="[0, 3, 4].includes(row.status)"
              size="small"
              type="danger"
              @click="onDelete(row)"
            >删除</el-button>
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
.card-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
}
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
.remark {
  color: #b3402a;
  font-size: 12px;
}
</style>
