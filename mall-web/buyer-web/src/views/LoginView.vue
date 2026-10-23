<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { fetchCaptcha, toDataUrl } from '../api/captcha'
import { useAuthStore } from '../stores/auth'

const router = useRouter()
const route = useRoute()
const auth = useAuthStore()

const form = reactive({ username: '', password: '', captchaKey: '', captchaCode: '' })
const captchaImg = ref('')
const error = ref(
  route.query.reason === 'portal'
    ? '当前浏览器已切换到商家/平台工作台，买家登录态已退出，请重新登录。'
    : '',
)
const loading = ref(false)

async function loadCaptcha(): Promise<void> {
  try {
    const captcha = await fetchCaptcha()
    form.captchaKey = captcha.key
    form.captchaCode = ''
    captchaImg.value = toDataUrl(captcha.imageBase64)
  } catch {
    error.value = '验证码加载失败，请稍后重试'
  }
}

async function onSubmit(): Promise<void> {
  if (loading.value) return
  error.value = ''
  loading.value = true
  try {
    await auth.login({ ...form })
    const redirect = typeof route.query.redirect === 'string' ? route.query.redirect : '/home'
    await router.replace(redirect)
  } catch (e) {
    error.value = e instanceof Error ? e.message : '登录失败'
    await loadCaptcha()
  } finally {
    loading.value = false
  }
}

onMounted(loadCaptcha)
</script>

<template>
  <div class="page">
    <div class="card">
      <h1 class="card__title">木集商城</h1>
      <p class="card__subtitle">买家中心 · 登录</p>

      <div v-if="error" class="alert">{{ error }}</div>

      <form @submit.prevent="onSubmit">
        <div class="field">
          <label class="field__label" for="username">用户名</label>
          <input id="username" v-model="form.username" class="input" autocomplete="username" required />
        </div>

        <div class="field">
          <label class="field__label" for="password">密码</label>
          <input
            id="password"
            v-model="form.password"
            class="input"
            type="password"
            autocomplete="current-password"
            required
          />
        </div>

        <div class="field">
          <label class="field__label" for="captcha">图形验证码</label>
          <div class="captcha-row">
            <input id="captcha" v-model="form.captchaCode" class="input" maxlength="4" required />
            <img :src="captchaImg" alt="验证码" title="点击刷新" @click="loadCaptcha" />
          </div>
        </div>

        <button class="btn" type="submit" :disabled="loading">
          {{ loading ? '登录中…' : '登 录' }}
        </button>
      </form>

      <p class="card__footer">
        还没有账号？<RouterLink to="/register">立即注册</RouterLink>
      </p>
    </div>
  </div>
</template>
