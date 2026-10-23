<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import http from '../api/http'
import { useAuthStore } from '../stores/auth'

const router = useRouter()
const route = useRoute()
const auth = useAuthStore()

const accountType = ref<'ADMIN' | 'MERCHANT'>('ADMIN')
const form = reactive({ username: '', password: '', captchaKey: '', captchaCode: '' })
const captchaImg = ref('')
const error = ref(
  route.query.reason === 'portal'
    ? '当前浏览器已切换到买家商城，工作台登录态已退出，请重新登录。'
    : '',
)
const loading = ref(false)

async function loadCaptcha(): Promise<void> {
  try {
    const res = await http.get('/captcha')
    const data = res.data.data as { key: string; imageBase64: string }
    form.captchaKey = data.key
    form.captchaCode = ''
    captchaImg.value = data.imageBase64.startsWith('data:')
      ? data.imageBase64
      : `data:image/png;base64,${data.imageBase64}`
  } catch {
    error.value = '验证码加载失败，请稍后重试'
  }
}

async function onSubmit(): Promise<void> {
  if (loading.value) return
  error.value = ''
  loading.value = true
  try {
    await auth.login(accountType.value, { ...form })
    ElMessage.success('登录成功')
    const redirect = typeof route.query.redirect === 'string' ? route.query.redirect : ''
    if (redirect) {
      await router.replace(redirect)
    } else if (auth.userInfo?.accountType === 'MERCHANT') {
      await router.replace({ name: 'mApply' })
    } else {
      await router.replace('/')
    }
  } catch (e) {
    error.value = e instanceof Error ? e.message : '登录失败'
    await loadCaptcha()
  } finally {
    loading.value = false
  }
}

function onTabChange(): void {
  error.value = ''
  loadCaptcha()
}

onMounted(loadCaptcha)
</script>

<template>
  <div class="page">
    <div class="card">
      <h1 class="card__title">木集商城</h1>
      <p class="card__subtitle">{{ accountType === 'ADMIN' ? '平台管理后台' : '商家工作台' }} · 登录</p>

      <el-tabs v-model="accountType" stretch style="margin-bottom: 16px" @tab-change="onTabChange">
        <el-tab-pane label="管理员" name="ADMIN" />
        <el-tab-pane label="商家" name="MERCHANT" />
      </el-tabs>

      <div v-if="error" class="alert">{{ error }}</div>

      <form @submit.prevent="onSubmit">
        <div class="field">
          <label class="field__label" for="username">{{ accountType === 'ADMIN' ? '管理员账号' : '商家账号' }}</label>
          <el-input id="username" v-model="form.username" placeholder="请输入账号" />
        </div>

        <div class="field">
          <label class="field__label" for="password">密码</label>
          <el-input id="password" v-model="form.password" type="password" show-password placeholder="请输入密码" />
        </div>

        <div class="field" :data-captcha-key="form.captchaKey" data-testid="captcha-field">
          <label class="field__label" for="captcha">图形验证码</label>
          <div class="captcha-row">
            <el-input id="captcha" v-model="form.captchaCode" maxlength="4" placeholder="4 位数字" />
            <img :src="captchaImg" alt="验证码" title="点击刷新" :data-captcha-key="form.captchaKey" @click="loadCaptcha" />
          </div>
        </div>

        <el-button type="primary" style="width: 100%" :loading="loading" @click="onSubmit">
          登 录
        </el-button>
      </form>

      <p v-if="accountType === 'ADMIN'" class="card__footer">管理员账号由平台统一维护，不开放自助注册</p>
      <p v-else class="card__footer">
        还没有商家账号？
        <RouterLink to="/register/merchant" class="link">立即入驻注册</RouterLink>
      </p>
    </div>
  </div>
</template>

<style scoped>
.link {
  color: var(--wood-600, #8b5a2b);
  text-decoration: none;
  font-weight: 600;
}
.link:hover {
  text-decoration: underline;
}
</style>
