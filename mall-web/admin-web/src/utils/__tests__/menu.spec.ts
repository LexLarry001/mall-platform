import { describe, it, expect } from 'vitest'
import { buildMenuTree } from '../menu'

describe('buildMenuTree', () => {
  it('乱序扁平列表 → 按 sort 排序的嵌套树', () => {
    const flat = [
      { id: 3, parentId: 1, name: '用户管理', code: 'system:user:list', type: 'MENU', path: '/system/user', sort: 2 },
      { id: 1, parentId: null, name: '系统管理', code: 'system', type: 'MENU', path: '/system', sort: 2 },
      { id: 4, parentId: 1, name: '角色管理', code: 'system:role:list', type: 'MENU', path: '/system/role', sort: 1 },
      { id: 2, parentId: null, name: '首页', code: 'home', type: 'MENU', path: '/home', sort: 1 },
    ]

    const tree = buildMenuTree(flat)

    expect(tree.map((n) => n.id)).toEqual([2, 1])
    expect(tree[1].children?.map((n) => n.id)).toEqual([4, 3])
  })
})
