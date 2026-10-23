import http from './http'

/** 搜索列表项（与后端 ProductListItemView 对齐；spuId 大雪花 ID 为字符串）。 */
export interface ProductItem {
  spuId: string
  name: string
  mainImg: string
  price: number
  sales: number
}

export interface PageResult<T> {
  records: T[]
  total: number
  current: number
  size: number
}

export type ProductSort = 'default' | 'sales' | 'price_asc' | 'price_desc' | 'new'

export interface SearchParams {
  keyword?: string
  categoryId?: number | string
  shopId?: number | string
  sort?: ProductSort
  page?: number
  size?: number
}

/** 商品详情聚合（与后端 ProductDetailVO 对齐）。 */
export interface ProductDetail {
  spu: {
    id: string
    shopId: string
    categoryId: string
    name: string
    subTitle: string | null
    mainImg: string
    imgList: string[]
    price: number
    sales: number
    avgRating: number
    commentCount: number
  }
  skus: Array<{
    id: string
    specValues: Record<string, string>
    price: number
    stock: number
    skuImg: string | null
  }>
  specs: Array<{ specName: string; values: string[] }>
  detailHtml: string | null
  shop: { id: string; name: string; logo: string | null; intro: string | null; bizStatus: number }
}

/** GET /api/product/search —— 已上架商品搜索（匿名可访问）。 */
export async function searchProducts(params: SearchParams): Promise<PageResult<ProductItem>> {
  const res = await http.get('/product/search', { params })
  return res.data.data as PageResult<ProductItem>
}

/** GET /api/product/hot-search —— 热搜词 Top10。 */
export async function getHotSearch(): Promise<string[]> {
  const res = await http.get('/product/hot-search')
  return (res.data.data || []) as string[]
}

/** GET /api/product/{spuId} —— 商品详情聚合；未上架由后端返回业务错误。 */
export async function getProductDetail(spuId: string): Promise<ProductDetail> {
  const res = await http.get(`/product/${spuId}`)
  return res.data.data as ProductDetail
}
