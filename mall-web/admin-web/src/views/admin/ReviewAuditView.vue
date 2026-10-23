<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { hideReview, listAdminReviews, restoreReview } from '../../api/review'
import type { AdminReview } from '../../api/review'

const loading = ref(false)
const list = ref<AdminReview[]>([])
const total = ref(0)
const pageNum = ref(1)
const pageSize = ref(10)
const statusFilter = ref<number | null>(null)
const ratingFilter = ref<number | null>(null)
const keyword = ref('')

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
    const res = await listAdminReviews({
      pageNum: pageNum.value,
      pageSize: pageSize.value,
      status: statusFilter.value,
      rating: ratingFilter.value,
      keyword: keyword.value.trim() || undefined,
    })
    list.value = res.records
    total.value = res.total
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '加载失败')
  } finally {
    loading.value = false
  }
}

function onFilter(): void {
  pageNum.value = 1
  void load()
}

function onPage(value: number): void {
  pageNum.value = value
  void load()
}

async function onHide(row: AdminReview): Promise<void> {
  let reason = ''
  try {
    const result = await ElMessageBox.prompt('隐藏后该评价不再展示给买家，原因仅平台留痕', '隐藏评价', {
      inputPlaceholder: '例如：含有广告或联系方式',
      inputValidator: (value: string) =>
        value && value.trim().length >= 2 && value.trim().length <= 200 ? true : '请填写 2~200 字的隐藏原因',
      confirmButtonText: '确认隐藏',
      cancelButtonText: '取消',
      type: 'warning',
    })
    reason = result.value.trim()
  } catch {
    return
  }
  try {
    await hideReview(row.id, reason)
    ElMessage.success('已隐藏')
    await load()
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '操作失败')
  }
}

async function onRestore(row: AdminReview): Promise<void> {
  try {
    await ElMessageBox.confirm('确认恢复展示该评价？商品评分将同步重算。', '恢复评价', {
      confirmButtonText: '恢复展示',
      cancelButtonText: '取消',
    })
  } catch {
    return
  }
  try {
    await restoreReview(row.id)
    ElMessage.success('已恢复')
    await load()
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '操作失败')
  }
}

onMounted(load)
</script>

<template>
  <div>
    <el-card>
      <template #header>
        <div class="card-header">
          <span>评价审核</span>
          <div class="card-header__actions">
            <el-input
              v-model="keyword"
              placeholder="商品 / 评价内容关键词"
              clearable
              style="width: 220px"
              @keyup.enter="onFilter"
              @clear="onFilter"
            />
            <el-select v-model="ratingFilter" placeholder="全部星级" clearable style="width: 120px" @change="onFilter">
              <el-option v-for="n in [5, 4, 3, 2, 1]" :key="n" :label="`${n} 星`" :value="n" />
            </el-select>
            <el-select v-model="statusFilter" placeholder="全部状态" clearable style="width: 120px" @change="onFilter">
              <el-option label="展示中" :value="1" />
              <el-option label="已隐藏" :value="0" />
            </el-select>
            <el-button type="primary" @click="onFilter">查询</el-button>
          </div>
        </div>
      </template>

      <el-table v-loading="loading" :data="list" border stripe>
        <el-table-column label="商品" min-width="220">
          <template #default="{ row }">
            <div class="product-cell">
              <img :src="row.mainImg" :alt="row.productName" class="product-cell__img" />
              <div>
                <div class="product-cell__name">{{ row.productName }}</div>
                <div class="product-cell__shop">{{ row.shopName }}</div>
              </div>
            </div>
          </template>
        </el-table-column>
        <el-table-column prop="userName" label="买家" width="110" />
        <el-table-column label="评分" width="140">
          <template #default="{ row }">
            <el-rate :model-value="row.rating" disabled />
          </template>
        </el-table-column>
        <el-table-column label="评价内容" min-width="300">
          <template #default="{ row }">
            <div class="content-cell">{{ row.content }}</div>
            <div v-if="row.images?.length" class="content-cell__images">
              <el-image
                v-for="img in row.images"
                :key="img"
                :src="img"
                :preview-src-list="row.images"
                fit="cover"
                class="content-cell__img"
              />
            </div>
            <div v-if="row.hideReason" class="hide-reason">隐藏原因：{{ row.hideReason }}</div>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="100">
          <template #default="{ row }">
            <el-tag v-if="row.status === 1" type="success" size="small">展示中</el-tag>
            <el-tag v-else type="danger" size="small">已隐藏</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="评价时间" width="170">
          <template #default="{ row }">{{ formatDateTime(row.createTime) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="100" fixed="right">
          <template #default="{ row }">
            <el-button v-if="row.status === 1" type="danger" link @click="onHide(row)">隐藏</el-button>
            <el-button v-else type="primary" link @click="onRestore(row)">恢复</el-button>
          </template>
        </el-table-column>
      </el-table>

      <div class="pager">
        <el-pagination
          background
          layout="total, prev, pager, next"
          :total="total"
          :current-page="pageNum"
          :page-size="pageSize"
          @current-change="onPage"
        />
      </div>
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
  gap: 10px;
  align-items: center;
}

.product-cell {
  display: flex;
  align-items: center;
  gap: 10px;
}

.product-cell__img {
  width: 48px;
  height: 48px;
  object-fit: cover;
  border-radius: 6px;
  border: 1px solid #eee3d5;
  flex: none;
}

.product-cell__name {
  font-size: 13px;
  line-height: 1.5;
}

.product-cell__shop {
  font-size: 12px;
  color: #8a7a66;
}

.content-cell {
  font-size: 13px;
  line-height: 1.6;
  color: #4a3a2a;
}

.content-cell__images {
  display: flex;
  gap: 6px;
  margin-top: 6px;
  flex-wrap: wrap;
}

.content-cell__img {
  width: 56px;
  height: 56px;
  border-radius: 6px;
}

.hide-reason {
  margin-top: 6px;
  font-size: 12px;
  color: #b33;
}

.pager {
  display: flex;
  justify-content: flex-end;
  margin-top: 16px;
}
</style>
