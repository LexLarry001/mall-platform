import { createRouter, createWebHistory } from 'vue-router'
import { useAuthStore } from '../stores/auth'
import { canAccess } from '../utils/merchant-guard'
import { readActivePortal } from '../utils/portal'

const router = createRouter({
  history: createWebHistory(),
  routes: [
    {
      path: '/login',
      name: 'login',
      component: () => import('../views/LoginView.vue'),
      meta: { public: true },
    },
    {
      path: '/register/merchant',
      name: 'merchantRegister',
      component: () => import('../views/MerchantRegisterView.vue'),
      meta: { public: true },
    },
    {
      path: '/',
      component: () => import('../layout/AdminLayout.vue'),
      children: [
        { path: '', name: 'dashboard', component: () => import('../views/DashboardView.vue') },
        // 平台端
        { path: 'merchant/audit', name: 'merchantAudit', component: () => import('../views/admin/MerchantAuditView.vue') },
        { path: 'system/user', name: 'systemUser', component: () => import('../views/admin/UserManageView.vue') },
        { path: 'system/role', name: 'systemRole', component: () => import('../views/admin/RoleManageView.vue') },
        { path: 'goods/category', name: 'categoryManage', component: () => import('../views/admin/CategoryManageView.vue') },
        { path: 'goods/audit', name: 'productAudit', component: () => import('../views/admin/ProductAuditView.vue') },
        { path: 'goods/manage', name: 'productManage', component: () => import('../views/admin/ProductManageView.vue') },
        { path: 'review/audit', name: 'reviewAudit', component: () => import('../views/admin/ReviewAuditView.vue') },
        // 商家端
        { path: 'm/apply', name: 'mApply', component: () => import('../views/merchant/ApplyView.vue') },
        { path: 'm/shop', name: 'mShop', component: () => import('../views/merchant/ShopView.vue') },
        { path: 'm/product', name: 'mProductList', component: () => import('../views/merchant/ProductListView.vue') },
        { path: 'm/product/edit', name: 'mProductEdit', component: () => import('../views/merchant/ProductEditView.vue') },
        { path: 'm/order', name: 'mOrder', component: () => import('../views/merchant/OrderManageView.vue') },
        { path: 'm/refund', name: 'mRefund', component: () => import('../views/merchant/RefundManageView.vue') },
        { path: 'm/review', name: 'mReview', component: () => import('../views/merchant/ReviewManageView.vue') },
      ],
    },
    {
      path: '/403',
      name: 'forbidden',
      component: () => import('../views/ForbiddenView.vue'),
      meta: { public: true },
    },
    {
      path: '/404',
      name: 'notFound',
      component: () => import('../views/NotFoundView.vue'),
      meta: { public: true },
    },
    { path: '/:pathMatch(.*)*', redirect: '/404' },
  ],
})

router.beforeEach(async (to) => {
  const auth = useAuthStore()
  const accountType = auth.userInfo?.accountType ?? ''

  // 同一浏览器已登录买家端：工作台登录态立即失效，阻止后退串页
  if (auth.token && readActivePortal() === 'buyer') {
    await auth.logout()
    return { name: 'login', query: { reason: 'portal' } }
  }

  if (to.meta.public) {
    if ((to.name === 'login' || to.name === 'merchantRegister') && auth.token) {
      return accountType === 'MERCHANT' ? { name: 'mApply' } : { name: 'dashboard' }
    }
    return true
  }

  if (!auth.token) {
    return { name: 'login', query: { redirect: to.fullPath } }
  }

  // 商家登录后刷新一次入驻状态，保证审核通过/驳回后菜单与守卫实时生效
  if (accountType === 'MERCHANT') {
    await auth.loadApplyStatus().catch(() => null)
    if (!canAccess(to.path, accountType, auth.auditStatus, [])) {
      return { name: 'mApply' }
    }
    return true
  }

  // 菜单已从后端按权限裁剪：非工作台路径必须命中授权菜单，否则 403（空菜单也不放行）
  if (!canAccess(to.path, accountType, auth.auditStatus, auth.allowedPaths)) {
    return { name: 'forbidden' }
  }

  return true
})

export default router
