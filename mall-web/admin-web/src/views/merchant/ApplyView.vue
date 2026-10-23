<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import http from '../../api/http'
import { useAuthStore } from '../../stores/auth'
import { uploadImage } from '../../utils/upload'

interface ApplyStatus {
  auditStatus: number | null
  auditRemark: string | null
  submitted: boolean
  subjectName: string | null
  licenseNo: string | null
  licenseImg: string | null
  shopName: string | null
  shopLogo: string | null
  shopIntro: string | null
}

const auth = useAuthStore()
const loading = ref(false)
const submitting = ref(false)
const error = ref('')
const status = ref<ApplyStatus | null>(null)

const form = reactive({
  subjectName: '',
  licenseNo: '',
  licenseImg: '',
  shopName: '',
  shopLogo: '',
  shopIntro: '',
})

const editable = computed(() => {
  if (!status.value) return true
  // 未提交过，或已被驳回 → 可填写/重填
  return !status.value.submitted || status.value.auditStatus === 2
})

async function load(): Promise<void> {
  loading.value = true
  try {
    const res = await http.get('/merchant/apply/status')
    status.value = res.data.data as ApplyStatus
    if (status.value) {
      form.subjectName = status.value.subjectName ?? ''
      form.licenseNo = status.value.licenseNo ?? ''
      form.licenseImg = status.value.licenseImg ?? ''
      form.shopName = status.value.shopName ?? ''
      form.shopLogo = status.value.shopLogo ?? ''
      form.shopIntro = status.value.shopIntro ?? ''
    }
  } catch (e) {
    error.value = e instanceof Error ? e.message : '加载失败'
  } finally {
    loading.value = false
  }
}

async function onUpload(field: 'licenseImg' | 'shopLogo', e: Event): Promise<void> {
  const input = e.target as HTMLInputElement
  const file = input.files?.[0]
  if (!file) return
  try {
    form[field] = await uploadImage(file)
    ElMessage.success('上传成功')
  } catch (err) {
    ElMessage.error(err instanceof Error ? err.message : '上传失败')
  } finally {
    input.value = ''
  }
}

async function onSubmit(): Promise<void> {
  if (submitting.value) return
  error.value = ''
  submitting.value = true
  try {
    await http.post('/merchant/apply', {
      subjectName: form.subjectName.trim(),
      licenseNo: form.licenseNo.trim(),
      licenseImg: form.licenseImg || null,
      shopName: form.shopName.trim(),
      shopLogo: form.shopLogo || null,
      shopIntro: form.shopIntro.trim() || null,
    })
    ElMessage.success('入驻申请已提交，请等待平台审核')
    await auth.loadApplyStatus()
    await load()
  } catch (e) {
    error.value = e instanceof Error ? e.message : '提交失败'
  } finally {
    submitting.value = false
  }
}

onMounted(load)
</script>

<template>
  <div v-loading="loading" class="apply">
    <el-card>
      <template #header>
        <div class="card-header">
          <span>入驻申请</span>
          <el-tag v-if="status?.submitted && status.auditStatus === 0" type="warning">审核中</el-tag>
          <el-tag v-else-if="status?.auditStatus === 1" type="success">已通过</el-tag>
          <el-tag v-else-if="status?.auditStatus === 2" type="danger">已驳回</el-tag>
          <el-tag v-else type="info">未提交</el-tag>
        </div>
      </template>

      <el-alert
        v-if="status?.auditStatus === 2"
        type="error"
        :title="`驳回原因：${status.auditRemark || '无'}`"
        description="请修改申请信息后重新提交"
        :closable="false"
        style="margin-bottom: 16px"
      />
      <el-alert
        v-else-if="status?.submitted && status.auditStatus === 0"
        type="warning"
        title="申请已提交，平台审核中，请耐心等待"
        :closable="false"
        style="margin-bottom: 16px"
      />
      <el-alert
        v-else-if="status?.auditStatus === 1"
        type="success"
        title="入驻审核已通过，店铺已开通，可前往店铺管理与商品管理"
        :closable="false"
        style="margin-bottom: 16px"
      />

      <el-form label-width="110px" :disabled="!editable" style="max-width: 640px">
        <el-form-item label="主体名称" required>
          <el-input v-model="form.subjectName" maxlength="64" placeholder="公司/个体户全称" />
        </el-form-item>
        <el-form-item label="证件号" required>
          <el-input v-model="form.licenseNo" maxlength="64" placeholder="统一社会信用代码/证件号" />
        </el-form-item>
        <el-form-item label="证件照">
          <div class="img-row">
            <img v-if="form.licenseImg" :src="form.licenseImg" alt="证件照" class="preview" />
            <input type="file" accept="image/jpeg,image/png,image/webp" @change="onUpload('licenseImg', $event)" />
            <el-input v-model="form.licenseImg" placeholder="或粘贴图片 URL" size="small" style="width: 240px" />
          </div>
        </el-form-item>
        <el-form-item label="店铺名称" required>
          <el-input v-model="form.shopName" maxlength="64" placeholder="审核通过后以此开店" />
        </el-form-item>
        <el-form-item label="店铺 Logo">
          <div class="img-row">
            <img v-if="form.shopLogo" :src="form.shopLogo" alt="店铺 Logo" class="preview" />
            <input type="file" accept="image/jpeg,image/png,image/webp" @change="onUpload('shopLogo', $event)" />
            <el-input v-model="form.shopLogo" placeholder="或粘贴图片 URL" size="small" style="width: 240px" />
          </div>
        </el-form-item>
        <el-form-item label="店铺简介">
          <el-input v-model="form.shopIntro" type="textarea" :rows="3" maxlength="500" />
        </el-form-item>
        <el-form-item v-if="editable">
          <el-button type="primary" :loading="submitting" @click="onSubmit">
            {{ status?.auditStatus === 2 ? '重新提交' : '提交申请' }}
          </el-button>
        </el-form-item>
      </el-form>

      <div v-if="error" class="alert">{{ error }}</div>
    </el-card>
  </div>
</template>

<style scoped>
.card-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
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
