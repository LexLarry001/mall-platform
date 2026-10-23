<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import http from '../../api/http'
import { uploadImage } from '../../utils/upload'

interface ShopInfo {
  id: number
  name: string
  logo: string | null
  intro: string | null
  notice: string | null
  bizStatus: number
}

const loading = ref(false)
const saving = ref(false)
const shop = ref<ShopInfo | null>(null)
const form = reactive({ name: '', logo: '', intro: '', notice: '' })

async function load(): Promise<void> {
  loading.value = true
  try {
    const res = await http.get('/merchant/shop/info')
    shop.value = res.data.data as ShopInfo
    form.name = shop.value.name ?? ''
    form.logo = shop.value.logo ?? ''
    form.intro = shop.value.intro ?? ''
    form.notice = shop.value.notice ?? ''
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '加载失败')
  } finally {
    loading.value = false
  }
}

async function onUpload(e: Event): Promise<void> {
  const input = e.target as HTMLInputElement
  const file = input.files?.[0]
  if (!file) return
  try {
    form.logo = await uploadImage(file)
    ElMessage.success('上传成功')
  } catch (err) {
    ElMessage.error(err instanceof Error ? err.message : '上传失败')
  } finally {
    input.value = ''
  }
}

async function onSave(): Promise<void> {
  if (saving.value) return
  saving.value = true
  try {
    await http.put('/merchant/shop/info', {
      name: form.name.trim(),
      logo: form.logo || null,
      intro: form.intro.trim() || null,
      notice: form.notice.trim() || null,
    })
    ElMessage.success('店铺信息已保存')
    await load()
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '保存失败')
  } finally {
    saving.value = false
  }
}

async function onBizToggle(value: boolean): Promise<void> {
  try {
    await http.post('/merchant/shop/biz-status', null, { params: { status: value ? 1 : 0 } })
    ElMessage.success(value ? '已恢复营业' : '已休店')
    await load()
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '操作失败')
  }
}

onMounted(load)
</script>

<template>
  <div v-loading="loading">
    <el-card v-if="shop">
      <template #header>
        <div class="card-header">
          <span>店铺管理</span>
          <div class="biz">
            <span class="biz__label">{{ shop.bizStatus === 1 ? '营业中' : '休店中' }}</span>
            <el-switch
              :model-value="shop.bizStatus === 1"
              active-text="营业"
              inactive-text="休店"
              @change="onBizToggle"
            />
          </div>
        </div>
      </template>

      <el-form label-width="110px" style="max-width: 640px">
        <el-form-item label="店铺名称" required>
          <el-input v-model="form.name" maxlength="64" />
        </el-form-item>
        <el-form-item label="店铺 Logo">
          <div class="img-row">
            <img v-if="form.logo" :src="form.logo" alt="店铺 Logo" class="preview" />
            <input type="file" accept="image/jpeg,image/png,image/webp" @change="onUpload" />
            <el-input v-model="form.logo" placeholder="或粘贴图片 URL" size="small" style="width: 240px" />
          </div>
        </el-form-item>
        <el-form-item label="店铺简介">
          <el-input v-model="form.intro" type="textarea" :rows="3" maxlength="500" />
        </el-form-item>
        <el-form-item label="店铺公告">
          <el-input v-model="form.notice" type="textarea" :rows="3" maxlength="500" />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" :loading="saving" @click="onSave">保存</el-button>
        </el-form-item>
      </el-form>
    </el-card>
  </div>
</template>

<style scoped>
.card-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
}
.biz {
  display: flex;
  align-items: center;
  gap: 10px;
}
.biz__label {
  color: #8b5a2b;
  font-weight: 600;
}
.img-row {
  display: flex;
  align-items: center;
  gap: 12px;
}
.preview {
  width: 72px;
  height: 72px;
  object-fit: cover;
  border-radius: 8px;
  border: 1px solid #e5d5c0;
}
</style>
