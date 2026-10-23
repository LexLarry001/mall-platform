import http from './http'

/** 后端雪花 ID 可能被序列化为字符串，统一用 Id 类型承载。 */
export type Id = string | number

export interface AdminUserView {
  id: Id
  username: string
  status: number
  createTime: string | number[] | null
  roleIds: Id[]
  roleNames: string[]
}

export interface AdminUserSaveRequest {
  username: string
  password?: string
  status: number
  roleIds: Id[]
}

export interface AdminRoleView {
  id: Id
  name: string
  code: string
  status: number
  createTime: string | number[] | null
  permissionIds: Id[]
}

export interface AdminRoleSaveRequest {
  name: string
  code: string
  status: number
  permissionIds: Id[]
}

export interface PermissionView {
  id: Id
  parentId: Id | null
  name: string
  code: string
  type: string
  path: string | null
  sort: number
  status: number
}

/** GET /api/admin/system/user/list —— 管理员账号列表。 */
export async function listUsers(): Promise<AdminUserView[]> {
  const res = await http.get('/admin/system/user/list')
  return res.data.data as AdminUserView[]
}

/** POST /api/admin/system/user —— 新增管理员。 */
export async function createUser(payload: AdminUserSaveRequest): Promise<Id> {
  const res = await http.post('/admin/system/user', payload)
  return res.data.data as Id
}

/** PUT /api/admin/system/user/{id} —— 编辑管理员。 */
export async function updateUser(id: Id, payload: AdminUserSaveRequest): Promise<void> {
  await http.put(`/admin/system/user/${id}`, payload)
}

/** POST /api/admin/system/user/{id}/status —— 启用/停用管理员。 */
export async function changeUserStatus(id: Id, status: number): Promise<void> {
  await http.post(`/admin/system/user/${id}/status`, null, { params: { status } })
}

/** DELETE /api/admin/system/user/{id} —— 删除管理员。 */
export async function deleteUser(id: Id): Promise<void> {
  await http.delete(`/admin/system/user/${id}`)
}

/** GET /api/admin/system/role/list —— 角色列表。 */
export async function listRoles(): Promise<AdminRoleView[]> {
  const res = await http.get('/admin/system/role/list')
  return res.data.data as AdminRoleView[]
}

/** GET /api/admin/system/role/permission/list —— 权限授权树（扁平节点）。 */
export async function listPermissions(): Promise<PermissionView[]> {
  const res = await http.get('/admin/system/role/permission/list')
  return res.data.data as PermissionView[]
}

/** POST /api/admin/system/role —— 新增角色。 */
export async function createRole(payload: AdminRoleSaveRequest): Promise<Id> {
  const res = await http.post('/admin/system/role', payload)
  return res.data.data as Id
}

/** PUT /api/admin/system/role/{id} —— 编辑角色并覆盖授权。 */
export async function updateRole(id: Id, payload: AdminRoleSaveRequest): Promise<void> {
  await http.put(`/admin/system/role/${id}`, payload)
}

/** POST /api/admin/system/role/{id}/status —— 启用/停用角色。 */
export async function changeRoleStatus(id: Id, status: number): Promise<void> {
  await http.post(`/admin/system/role/${id}/status`, null, { params: { status } })
}

/** DELETE /api/admin/system/role/{id} —— 删除角色。 */
export async function deleteRole(id: Id): Promise<void> {
  await http.delete(`/admin/system/role/${id}`)
}
