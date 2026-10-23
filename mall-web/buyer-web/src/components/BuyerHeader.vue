<script setup lang="ts">
import { ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useAuthStore } from '../stores/auth'
import AiAssistant from './AiAssistant.vue'

withDefaults(defineProps<{ showSearch?: boolean }>(), { showSearch: true })

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()

const keyword = ref((typeof route.query.keyword === 'string' ? route.query.keyword : '') as string)
watch(
  () => route.query.keyword,
  (v) => {
    keyword.value = typeof v === 'string' ? v : ''
  },
)

function onSearch(): void {
  const kw = keyword.value.trim()
  void router.push({ name: 'productSearch', query: kw ? { keyword: kw } : {} })
}

async function onLogout(): Promise<void> {
  await auth.logout()
  await router.push({ name: 'home' })
}
</script>

<template>
  <header class="buyer-header">
    <div class="buyer-header__inner">
      <RouterLink class="logo" :to="{ name: 'home' }">木集商城</RouterLink>
      <div v-if="showSearch" class="search-box">
        <input
          v-model="keyword"
          class="search-box__input"
          type="text"
          placeholder="搜索心仪的商品"
          @keyup.enter="onSearch"
        />
        <button class="search-box__btn" @click="onSearch">搜索</button>
      </div>
      <AiAssistant />
      <nav class="header-nav">
        <template v-if="auth.token">
          <RouterLink :to="{ name: 'cart' }">购物车</RouterLink>
          <RouterLink :to="{ name: 'orders' }">我的订单</RouterLink>
          <RouterLink :to="{ name: 'address' }">收货地址</RouterLink>
          <span class="header-nav__user">{{ auth.userInfo?.username }}</span>
          <a href="javascript:;" @click="onLogout">退出</a>
        </template>
        <template v-else>
          <RouterLink :to="{ name: 'login' }">登录</RouterLink>
          <RouterLink :to="{ name: 'register' }">注册</RouterLink>
        </template>
      </nav>
    </div>
  </header>
</template>

<style scoped>
.buyer-header {
  background: var(--wood-surface);
  border-bottom: 1px solid var(--wood-border);
  position: sticky;
  top: 0;
  z-index: 10;
}

.buyer-header__inner {
  max-width: 1200px;
  margin: 0 auto;
  padding: 14px 16px;
  display: flex;
  align-items: center;
  gap: 24px;
}

.logo {
  font-family: Georgia, 'STSong', 'SimSun', serif;
  font-size: 24px;
  font-weight: bold;
  color: var(--wood-primary-dark);
  text-decoration: none;
  letter-spacing: 0.06em;
  white-space: nowrap;
}

.search-box {
  flex: 1;
  display: flex;
  max-width: 520px;
}

.search-box__input {
  flex: 1;
  height: 38px;
  padding: 0 14px;
  border: 1px solid var(--wood-primary);
  border-right: none;
  border-radius: 8px 0 0 8px;
  background: #fff;
  color: var(--wood-text);
  font-size: 14px;
  outline: none;
}

.search-box__btn {
  width: 88px;
  height: 38px;
  border: none;
  border-radius: 0 8px 8px 0;
  background: var(--wood-primary);
  color: #fff;
  font-size: 14px;
  letter-spacing: 0.1em;
  cursor: pointer;
}

.search-box__btn:hover {
  background: var(--wood-primary-dark);
}

.header-nav {
  display: flex;
  align-items: center;
  gap: 14px;
  font-size: 14px;
  white-space: nowrap;
}

.header-nav a {
  color: var(--wood-primary);
  text-decoration: none;
}

.header-nav a:hover {
  color: var(--wood-primary-dark);
  text-decoration: underline;
}

.header-nav__user {
  color: var(--wood-text);
}
</style>
