<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { listMerchantReviews, replyReview } from '../../api/review'
import type { MerchantReview } from '../../api/review'

const loading = ref(false)
const list = ref<MerchantReview[]>([])
const total = ref(0)
const pageNum = ref(1)
const pageSize = ref(10)
const ratingFilter = ref<number | null>(null)
const repliedFilter = ref<boolean | null>(null)

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
    const res = await listMerchantReviews({
      pageNum: pageNum.value,
      pageSize: pageSize.value,
      rating: ratingFilter.value,
      replied: repliedFilter.value,
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

/** 回复评价：商家每条评价只能回复一次，提交后不可修改。 */
async function onReply(row: MerchantReview): Promise<void> {
  let reply = ''
  try {
    const result = await ElMessageBox.prompt('回复将展示在商品评价下方（仅可回复一次）', `回复 ${row.userName} 的评价`, {
      inputType: 'textarea',
      inputPlaceholder: '例如：感谢您的反馈，我们会持续改进做工细节',
      inputValidator: (value: string) =>
        value && value.trim().length >= 2 && value.trim().length <= 300 ? true : '回复内容需 2~300 字',
      confirmButtonText: '提交回复',
      cancelButtonText: '取消',
    })
    reply = result.value.trim()
  } catch {
    return
  }
  try {
    await replyReview(row.id, reply)
    ElMessage.success('回复成功')
    await load()
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '回复失败')
  }
}

onMounted(load)
</script>

<template>
  <div>
    <el-card>
      <template #header>
        <div class="card-header">
          <span>评价管理</span>
          <div class="card-header__actions">
            <el-select v-model="ratingFilter" placeholder="全部星级" clearable style="width: 130px" @change="onFilter">
              <el-option v-for="n in [5, 4, 3, 2, 1]" :key="n" :label="`${n} 星`" :value="n" />
            </el-select>
            <el-select v-model="repliedFilter" placeholder="全部状态" clearable style="width: 130px" @change="onFilter">
              <el-option label="待回复" :value="false" />
              <el-option label="已回复" :value="true" />
            </el-select>
          </div>
        </div>
      </template>

      <el-table v-loading="loading" :data="list" border stripe>
        <el-table-column label="商品" min-width="230">
          <template #default="{ row }">
            <div class="product-cell">
              <img :src="row.mainImg" :alt="row.productName" class="product-cell__img" />
              <span class="product-cell__name">{{ row.productName }}</span>
            </div>
          </template>
        </el-table-column>
        <el-table-column prop="userName" label="买家" width="120" />
        <el-table-column label="评分" width="150">
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
            <div v-if="row.tags?.length" class="content-cell__tags">
              <el-tag v-for="t in row.tags" :key="t" size="small" type="info">{{ t }}</el-tag>
            </div>
            <div v-if="row.merchantReply" class="reply-cell">
              <b>我的回复：</b>{{ row.merchantReply }}
            </div>
          </template>
        </el-table-column>
        <el-table-column label="评价时间" width="170">
          <template #default="{ row }">{{ formatDateTime(row.createTime) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="110" fixed="right">
          <template #default="{ row }">
            <el-button v-if="!row.merchantReply" type="primary" link @click="onReply(row)">回复</el-button>
            <span v-else class="replied-text">已回复</span>
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

.content-cell__tags {
  display: flex;
  gap: 6px;
  flex-wrap: wrap;
  margin-top: 6px;
}

.reply-cell {
  margin-top: 8px;
  padding: 6px 8px;
  border-radius: 6px;
  background: #fdf8f1;
  font-size: 12px;
  color: #8a7a66;
  line-height: 1.6;
}

.replied-text {
  font-size: 12px;
  color: #3d7a4a;
}

.pager {
  display: flex;
  justify-content: flex-end;
  margin-top: 16px;
}
</style>
