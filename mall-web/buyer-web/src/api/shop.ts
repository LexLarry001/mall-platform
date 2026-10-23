import http from './http'

/** 店铺公开信息（与后端 ShopPublicView 对齐）。 */
export interface ShopInfo {
  name: string
  logo: string | null
  intro: string | null
  bizStatus: number
  productCount: number
}

/** GET /api/shop/{shopId} —— 店铺公开信息 + 已上架商品数（匿名可访问）。 */
export async function getShopInfo(shopId: string): Promise<ShopInfo> {
  const res = await http.get(`/shop/${shopId}`)
  return res.data.data as ShopInfo
}
