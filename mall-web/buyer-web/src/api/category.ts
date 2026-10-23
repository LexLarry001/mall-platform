import http from './http'

/** 类目树节点（买家端仅返回启用类目；大雪花 ID 由后端序列化为字符串）。 */
export interface CategoryNode {
  id: number | string
  parentId: number | string
  name: string
  sort: number
  status: number
  children: CategoryNode[]
}

/** GET /api/category/tree —— 买家类目导航（匿名可访问）。 */
export async function getCategoryTree(): Promise<CategoryNode[]> {
  const res = await http.get('/category/tree')
  return (res.data.data || []) as CategoryNode[]
}
