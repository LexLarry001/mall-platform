<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { fetchCaptcha, toDataUrl } from '../api/captcha'
import { useAuthStore } from '../stores/auth'

const router = useRouter()
const auth = useAuthStore()

const form = reactive({
  username: '',
  password: '',
  confirmPassword: '',
  captchaKey: '',
  captchaCode: '',
})
const captchaImg = ref('')
const error = ref('')
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
  if (form.password !== form.confirmPassword) {
    error.value = '两次输入的密码不一致'
    return
  }
  loading.value = true
  try {
    await auth.register({
      username: form.username,
      password: form.password,
      captchaKey: form.captchaKey,
      captchaCode: form.captchaCode,
    })
    await router.replace({ name: 'login' })
  } catch (e) {
    error.value = e instanceof Error ? e.message : '注册失败'
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
      <p class="card__subtitle">买家中心 · 注册</p>

      <div v-if="error" class="alert">{{ error }}</div>

      <form @submit.prevent="onSubmit">
        <div class="field">
          <label class="field__label" for="username">用户名</label>
          <input id="username" v-model="form.username" class="input" required />
        </div>

        <div class="field">
          <label class="field__label" for="password">密码</label>
          <input id="password" v-model="form.password" class="input" type="password" required />
        </div>

        <div class="field">
          <label class="field__label" for="confirm">确认密码</label>
          <input id="confirm" v-model="form.confirmPassword" class="input" type="password" required />
        </div>

        <div class="field">
          <label class="field__label" for="captcha">图形验证码</label>
          <div class="captcha-row">
            <input id="captcha" v-model="form.captchaCode" class="input" maxlength="4" required />
            <img :src="captchaImg" alt="验证码" title="点击刷新" @click="loadCaptcha" />
          </div>
        </div>

        <button class="btn" type="submit" :disabled="loading">
          {{ loading ? '注册中…' : '注 册' }}
        </button>
      </form>

      <p class="card__footer">
        已有账号？<RouterLink to="/login">返回登录</RouterLink>
      </p>
    </div>
  </div>
</template>
