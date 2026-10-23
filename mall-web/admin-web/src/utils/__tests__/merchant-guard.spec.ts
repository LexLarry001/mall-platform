import { describe, it, expect } from 'vitest'
import { canAccess, visibleMenus } from '../merchant-guard'
import type { MenuNode } from '../menu'

const dynamicMenus: MenuNode[] = [
  { id: 1, parentId: null, name: '商家管理', code: 'merchant', type: 'MENU', path: '/merchant', sort: 1 },
  { id: 2, parentId: 1, name: '入驻审核', code: 'merchant:audit:list', type: 'MENU', path: '/merchant/audit', sort: 1 },
]

describe('visibleMenus', () => {
  it('商家 auditStatus=0 → 仅入驻申请可见', () => {
    const menus = visibleMenus('MERCHANT', 0, dynamicMenus)
    expect(menus.map((m) => m.path)).toEqual(['/m/apply'])
  })

  it('商家 auditStatus=2 → 仅入驻申请可见', () => {
    const menus = visibleMenus('MERCHANT', 2, dynamicMenus)
    expect(menus.map((m) => m.path)).toEqual(['/m/apply'])
  })

  it('商家 auditStatus=1 → 五菜单（含售后处理）', () => {
    const menus = visibleMenus('MERCHANT', 1, dynamicMenus)
    expect(menus.map((m) => m.path)).toEqual([
      '/m/apply',
      '/m/shop',
      '/m/product',
      '/m/order',
      '/m/refund',
    ])
  })

  it('ADMIN → 使用后端动态菜单', () => {
    const menus = visibleMenus('ADMIN', null, dynamicMenus)
    expect(menus).toEqual(dynamicMenus)
  })
})

describe('canAccess', () => {
  it('未过审商家仅放行 /m/apply，其余路径拒绝', () => {
    expect(canAccess('/m/apply', 'MERCHANT', 0, [])).toBe(true)
    expect(canAccess('/m/shop', 'MERCHANT', 0, [])).toBe(false)
    expect(canAccess('/m/product', 'MERCHANT', 2, [])).toBe(false)
  })

  it('已过审商家放行全部 /m/**，平台路径拒绝', () => {
    expect(canAccess('/m/shop', 'MERCHANT', 1, [])).toBe(true)
    expect(canAccess('/m/product/edit', 'MERCHANT', 1, [])).toBe(true)
    expect(canAccess('/merchant/audit', 'MERCHANT', 1, [])).toBe(false)
  })

  it('ADMIN 按授权路径放行，越界路径 false', () => {
    const allowed = ['/merchant', '/merchant/audit']
    expect(canAccess('/', 'ADMIN', null, allowed)).toBe(true)
    expect(canAccess('/merchant/audit', 'ADMIN', null, allowed)).toBe(true)
    expect(canAccess('/goods/audit', 'ADMIN', null, allowed)).toBe(false)
  })
})
