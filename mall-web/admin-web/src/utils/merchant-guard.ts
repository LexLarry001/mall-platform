import type { MenuNode } from './menu'

/**
 * 商家端前端静态菜单（平台端菜单走后端 RBAC 动态下发）。
 * 入驻审核未通过（auditStatus !== 1）的商家只可见「入驻申请」。
 */
const MERCHANT_MENU_APPLY: MenuNode = {
  id: -1,
  parentId: null,
  name: '入驻申请',
  code: 'm:apply',
  type: 'MENU',
  path: '/m/apply',
  sort: 1,
}

const MERCHANT_MENUS_APPROVED: MenuNode[] = [
  MERCHANT_MENU_APPLY,
  { id: -2, parentId: null, name: '店铺管理', code: 'm:shop', type: 'MENU', path: '/m/shop', sort: 2 },
  { id: -3, parentId: null, name: '商品管理', code: 'm:product', type: 'MENU', path: '/m/product', sort: 3 },
  { id: -4, parentId: null, name: '订单管理', code: 'm:order', type: 'MENU', path: '/m/order', sort: 4 },
  { id: -5, parentId: null, name: '售后处理', code: 'm:refund', type: 'MENU', path: '/m/refund', sort: 5 },
  { id: -6, parentId: null, name: '评价管理', code: 'm:review', type: 'MENU', path: '/m/review', sort: 6 },
]

/**
 * 按账号类型与入驻审核状态计算侧边栏可见菜单。
 * - ADMIN（及其他）：使用后端 RBAC 动态菜单
 * - MERCHANT + auditStatus=1：入驻申请 / 店铺管理 / 商品管理
 * - MERCHANT + 其他状态：仅入驻申请
 */
export function visibleMenus(
  accountType: string,
  auditStatus: number | null,
  dynamicMenus: MenuNode[],
): MenuNode[] {
  if (accountType === 'MERCHANT') {
    return auditStatus === 1 ? MERCHANT_MENUS_APPROVED : [MERCHANT_MENU_APPLY]
  }
  return dynamicMenus
}

/**
 * 路由守卫放行判定。
 * - MERCHANT：只放行 /m/**；未过审（auditStatus !== 1）仅放行 /m/apply
 * - ADMIN：工作台 / 放行，其余路径必须命中授权菜单路径
 */
export function canAccess(
  path: string,
  accountType: string,
  auditStatus: number | null,
  allowedPaths: string[],
): boolean {
  if (accountType === 'MERCHANT') {
    if (auditStatus === 1) {
      return path.startsWith('/m/')
    }
    return path === '/m/apply'
  }
  if (path === '/') {
    return true
  }
  return allowedPaths.includes(path)
}
