<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import http from '../../api/http'

interface AuditProduct {
  id: number
  shopId: number
  shopName: string
  name: string
  subTitle: string | null
  mainImg: string
  price: number
  createTime: string
}

interface Page<T> {
  records: T[]
  total: number
}

const loading = ref(false)
const list = ref<AuditProduct[]>([])
const total = ref(0)
const query = reactive({ keyword: '', page: 1, size: 10 })

async function load(): Promise<void> {
  loading.value = true
  try {
    const params: Record<string, unknown> = { pageNum: query.page, pageSize: query.size }
    if (query.keyword.trim()) params.keyword = query.keyword.trim()
    const res = await http.get('/admin/product/audit/list', { params })
    const page = res.data.data as Page<AuditProduct>
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

async function onPass(row: AuditProduct): Promise<void> {
  try {
    await ElMessageBox.confirm(`确定通过商品「${row.name}」并上架吗？`, '审核确认', { type: 'warning' })
  } catch {
    return
  }
  try {
    await http.post(`/admin/product/${row.id}/audit/pass`)
    ElMessage.success('已通过并上架')
    load()
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '操作失败')
  }
}

async function onReject(row: AuditProduct): Promise<void> {
  let remark = ''
  try {
    const result = await ElMessageBox.prompt('请输入驳回原因', `驳回「${row.name}」`, {
      type: 'warning',
      inputValidator: (v) => (v && v.trim() ? true : '驳回原因不能为空'),
    })
    remark = result.value.trim()
  } catch {
    return
  }
  try {
    await http.post(`/admin/product/${row.id}/audit/reject`, null, { params: { remark } })
    ElMessage.success('已驳回')
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
      <template #header><span>商品审核（待审核列表）</span></template>

      <div class="toolbar">
        <el-input v-model="query.keyword" placeholder="商品名称关键词" style="width: 240px" clearable @keyup.enter="onSearch" />
        <el-button type="primary" plain @click="onSearch">查询</el-button>
      </div>

      <el-table v-loading="loading" :data="list" style="width: 100%">
        <el-table-column label="商品" min-width="240">
          <template #default="{ row }">
            <div class="prod">
              <img :src="row.mainImg" alt="" class="prod__img" />
              <div>
                <div>{{ row.name }}</div>
                <div class="prod__sub">{{ row.subTitle || '-' }}</div>
              </div>
            </div>
          </template>
        </el-table-column>
        <el-table-column prop="shopName" label="店铺" min-width="130" />
        <el-table-column label="价格" width="110">
          <template #default="{ row }">¥ {{ Number(row.price).toFixed(2) }}</template>
        </el-table-column>
        <el-table-column prop="createTime" label="提交时间" width="170" />
        <el-table-column label="操作" width="180" fixed="right">
          <template #default="{ row }">
            <el-button size="small" type="success" @click="onPass(row)">通过上架</el-button>
            <el-button size="small" type="danger" @click="onReject(row)">驳回</el-button>
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
.prod__sub {
  color: #9c8465;
  font-size: 12px;
}
</style>
