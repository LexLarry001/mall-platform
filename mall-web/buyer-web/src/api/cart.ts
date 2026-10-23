import http from './http'
import type { CartItemLike } from '../utils/cart-group'

/** 购物车列表项（与后端 CartItemView 对齐）。 */
export type CartItem = CartItemLike

/** GET /api/user/cart —— 我的购物车（失效项打标 invalid）。 */
export async function listCart(): Promise<CartItem[]> {
  const res = await http.get('/user/cart')
  return res.data.data as CartItem[]
}

/** POST /api/user/cart —— 加购（同款合并数量）。 */
export async function addToCart(skuId: string, quantity: number): Promise<void> {
  await http.post('/user/cart', { skuId, quantity })
}

/** PUT /api/user/cart/{skuId} —— 改数量/勾选（缺省字段保持原值）。 */
export async function updateCartItem(
  skuId: string,
  patch: { quantity?: number; checked?: boolean },
): Promise<void> {
  await http.put(`/user/cart/${skuId}`, patch)
}

/** DELETE /api/user/cart/{skuId} —— 移除条目。 */
export async function removeCartItem(skuId: string): Promise<void> {
  await http.delete(`/user/cart/${skuId}`)
}
