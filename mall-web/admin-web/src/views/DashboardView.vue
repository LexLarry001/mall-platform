<script setup lang="ts">
import { useAuthStore } from '../stores/auth'

const auth = useAuthStore()
</script>

<template>
  <div>
    <h2>工作台</h2>
    <div class="stat-grid">
      <div class="stat">
        <div class="stat__label">当前账号</div>
        <div class="stat__value">{{ auth.userInfo?.username || '-' }}</div>
      </div>
      <div class="stat">
        <div class="stat__label">账号 ID</div>
        <div class="stat__value">{{ auth.userInfo?.accountId || '-' }}</div>
      </div>
      <div class="stat">
        <div class="stat__label">可访问菜单</div>
        <div class="stat__value">{{ auth.menus.length }}</div>
      </div>
    </div>
    <p class="card__subtitle" style="margin-top: 20px">
      M1 阶段仅打通认证权限闭环：登录 → 动态菜单 → 越权访问 403。业务管理页面将在后续里程碑开放。
    </p>
  </div>
</template>
