import { describe, it, expect, vi, beforeEach } from 'vitest'

const httpMock = vi.hoisted(() => ({
  get: vi.fn(),
  post: vi.fn(),
  put: vi.fn(),
  delete: vi.fn(),
}))

vi.mock('../http', () => ({ default: httpMock }))

import {
  listUsers,
  createUser,
  updateUser,
  changeUserStatus,
  deleteUser,
  listRoles,
  listPermissions,
  createRole,
  updateRole,
  changeRoleStatus,
  deleteRole,
} from '../system'

/** 后端统一响应包装：{ code, message, data }。 */
function ok<T>(data: T) {
  return { data: { code: 0, message: 'success', data } }
}

describe('system api', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('用户管理接口路径与入参正确', async () => {
    httpMock.get.mockResolvedValueOnce(ok([{ id: '1', username: 'admin' }]))
    await expect(listUsers()).resolves.toEqual([{ id: '1', username: 'admin' }])
    expect(httpMock.get).toHaveBeenCalledWith('/admin/system/user/list')

    const payload = { username: 'alice', password: 'secret123', status: 1, roleIds: [1] }
    httpMock.post.mockResolvedValueOnce(ok(1001))
    await expect(createUser(payload)).resolves.toBe(1001)
    expect(httpMock.post).toHaveBeenCalledWith('/admin/system/user', payload)

    await updateUser(1001, payload)
    expect(httpMock.put).toHaveBeenCalledWith('/admin/system/user/1001', payload)

    await changeUserStatus(1001, 0)
    expect(httpMock.post).toHaveBeenCalledWith('/admin/system/user/1001/status', null, {
      params: { status: 0 },
    })

    await deleteUser(1001)
    expect(httpMock.delete).toHaveBeenCalledWith('/admin/system/user/1001')
  })

  it('角色管理接口路径与入参正确', async () => {
    httpMock.get.mockResolvedValueOnce(ok([{ id: 1, code: 'ROLE_SUPER' }]))
    await expect(listRoles()).resolves.toEqual([{ id: 1, code: 'ROLE_SUPER' }])
    expect(httpMock.get).toHaveBeenCalledWith('/admin/system/role/list')

    httpMock.get.mockResolvedValueOnce(ok([{ id: 1, code: 'system:user:list' }]))
    await expect(listPermissions()).resolves.toEqual([{ id: 1, code: 'system:user:list' }])
    expect(httpMock.get).toHaveBeenCalledWith('/admin/system/role/permission/list')

    const payload = { name: '运营', code: 'ROLE_OPS', status: 1, permissionIds: [1, 2] }
    httpMock.post.mockResolvedValueOnce(ok(2001))
    await expect(createRole(payload)).resolves.toBe(2001)
    expect(httpMock.post).toHaveBeenCalledWith('/admin/system/role', payload)

    await updateRole(2001, payload)
    expect(httpMock.put).toHaveBeenCalledWith('/admin/system/role/2001', payload)

    await changeRoleStatus(2001, 0)
    expect(httpMock.post).toHaveBeenCalledWith('/admin/system/role/2001/status', null, {
      params: { status: 0 },
    })

    await deleteRole(2001)
    expect(httpMock.delete).toHaveBeenCalledWith('/admin/system/role/2001')
  })
})
