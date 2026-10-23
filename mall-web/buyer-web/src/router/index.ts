import { createRouter, createWebHistory } from 'vue-router'
import { useAuthStore } from '../stores/auth'
import { readActivePortal } from '../utils/portal'

const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/', redirect: '/home' },
    {
      path: '/login',
      name: 'login',
      component: () => import('../views/LoginView.vue'),
      meta: { public: true, guestOnly: true },
    },
    {
      path: '/register',
      name: 'register',
      component: () => import('../views/RegisterView.vue'),
      meta: { public: true, guestOnly: true },
    },
    { path: '/home', name: 'home', component: () => import('../views/HomeView.vue'), meta: { public: true } },
    {
      path: '/products',
      name: 'products',
      component: () => import('../views/ProductSearchView.vue'),
      meta: { public: true },
    },
    {
      path: '/search',
      name: 'productSearch',
      component: () => import('../views/ProductSearchView.vue'),
      meta: { public: true },
    },
    {
      path: '/shop/:id',
      name: 'shop',
      component: () => import('../views/ShopView.vue'),
      meta: { public: true },
    },
    {
      path: '/product/:id',
      name: 'productDetail',
      component: () => import('../views/ProductDetailView.vue'),
      meta: { public: true },
    },
    // 交易域（M3）：需登录，未登录由守卫跳 /login 并回跳
    { path: '/cart', name: 'cart', component: () => import('../views/CartView.vue') },
    { path: '/checkout', name: 'checkout', component: () => import('../views/CheckoutView.vue') },
    { path: '/orders', name: 'orders', component: () => import('../views/OrderListView.vue') },
    { path: '/orders/:id', name: 'orderDetail', component: () => import('../views/OrderDetailView.vue') },
    // 支付域（M4）：支付宝 return_url 同步跳回地址，需登录（守卫自动处理）
    { path: '/pay/result', name: 'payResult', component: () => import('../views/PayResultView.vue') },
    { path: '/address', name: 'address', component: () => import('../views/AddressView.vue') },
    { path: '/:pathMatch(.*)*', redirect: '/home' },
  ],
})

router.beforeEach(async (to) => {
  const auth = useAuthStore()

  // 同一浏览器已切换到商家/平台门户时，买家端登录态立即失效
  if (auth.token && ['merchant', 'admin'].includes(readActivePortal())) {
    await auth.logout()
    return { name: 'login', query: { reason: 'portal' } }
  }

  if (!to.meta.public && !auth.token) {
    return { name: 'login', query: { redirect: to.fullPath } }
  }
  // 仅登录/注册页对已有登录态的用户跳回首页；浏览页登录/匿名均可访问
  if (to.meta.guestOnly && auth.token) {
    return { name: 'home' }
  }
  return true
})

export default router
