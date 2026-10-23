<script setup lang="ts">
import { useRouter } from 'vue-router'

const router = useRouter()
</script>

<template>
  <div class="page">
    <div class="card page--center">
      <h1 class="card__title">404</h1>
      <p class="card__subtitle">页面不存在</p>
      <el-button type="primary" @click="router.replace('/')">返回首页</el-button>
    </div>
  </div>
</template>
