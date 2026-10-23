<script setup lang="ts">
import { reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import http from '../api/http'

const router = useRouter()

const form = reactive({ username: '', password: '', confirmPassword: '', contactPhone: '' })
const error = ref('')
const loading = ref(false)

async function onSubmit(): Promise<void> {
  if (loading.value) return
  error.value = ''
  if (!form.username.trim()) {
    error.value = '请输入用户名'
    return
  }
  if (form.password.length < 6) {
    error.value = '密码长度至少 6 位'
    return
  }
  if (form.password !== form.confirmPassword) {
    error.value = '两次输入的密码不一致'
    return
  }
  loading.value = true
  try {
    await http.post('/merchant/auth/register', {
      username: form.username.trim(),
      password: form.password,
      contactPhone: form.contactPhone.trim() || null,
    })
    ElMessage.success('注册成功，请登录后提交入驻申请')
    await router.replace({ name: 'login' })
  } catch (e) {
    error.value = e instanceof Error ? e.message : '注册失败'
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <div class="page">
    <div class="card">
      <h1 class="card__title">木集商城</h1>
      <p class="card__subtitle">商家入驻 · 账号注册</p>

      <div v-if="error" class="alert">{{ error }}</div>

      <form @submit.prevent="onSubmit">
        <div class="field">
          <label class="field__label" for="username">用户名</label>
          <el-input id="username" v-model="form.username" placeholder="登录账号，全局唯一" />
        </div>

        <div class="field">
          <label class="field__label" for="password">密码</label>
          <el-input id="password" v-model="form.password" type="password" show-password placeholder="至少 6 位" />
        </div>

        <div class="field">
          <label class="field__label" for="confirm">确认密码</label>
          <el-input id="confirm" v-model="form.confirmPassword" type="password" show-password placeholder="再次输入密码" />
        </div>

        <div class="field">
          <label class="field__label" for="phone">联系电话</label>
          <el-input id="phone" v-model="form.contactPhone" placeholder="选填，用于平台联系" />
        </div>

        <el-button type="primary" style="width: 100%" :loading="loading" @click="onSubmit">
          注 册
        </el-button>
      </form>

      <p class="card__footer">
        已有账号？
        <RouterLink to="/login" class="link">返回登录</RouterLink>
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
