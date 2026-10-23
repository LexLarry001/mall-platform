<script setup lang="ts">
import { computed } from 'vue'
import { useRouter } from 'vue-router'
import { useAuthStore } from '../stores/auth'
import SidebarItem from './SidebarItem.vue'

const router = useRouter()
const auth = useAuthStore()

const isMerchant = computed(() => auth.userInfo?.accountType === 'MERCHANT')
const brand = computed(() => (isMerchant.value ? '木集 · 商家工作台' : '木集 · 平台管理'))
const roleLabel = computed(() => (isMerchant.value ? '入驻商家' : '平台管理员'))

async function onLogout(): Promise<void> {
  await auth.logout()
  await router.replace({ name: 'login' })
}
</script>

<template>
  <div class="admin-shell">
    <aside class="sidebar">
      <div class="sidebar__brand">{{ brand }}</div>
      <div class="sidebar__scroll">
        <ul class="menu">
          <SidebarItem v-for="node in auth.menuTree" :key="node.id" :node="node" />
        </ul>
        <p v-if="auth.menuTree.length === 0" class="menu__group">暂无可访问菜单</p>
      </div>
      <div class="sidebar__footer">
        <el-button style="width: 100%" @click="onLogout">退出登录</el-button>
      </div>
    </aside>

    <section class="admin-main">
      <header class="admin-header">
        <span class="admin-header__user">{{ auth.userInfo?.username || '未登录' }} · {{ roleLabel }}</span>
      </header>
      <main class="admin-content">
        <RouterView />
      </main>
    </section>
  </div>
</template>
