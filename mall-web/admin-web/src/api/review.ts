import http from './http'

export type DateTimeValue = string | number[] | null

/** 商家评价列表项（与后端 MerchantReviewView 对齐）。 */
export interface MerchantReview {
  id: string
  spuId: string
  productName: string
  mainImg: string
  rating: number
  content: string
  images: string[]
  tags: string[]
  userName: string
  merchantReply: string | null
  replyTime: DateTimeValue
  createTime: DateTimeValue
}

/** 平台评价列表项（与后端 AdminReviewView 对齐）。 */
export interface AdminReview {
  id: string
  shopId: string
  shopName: string
  spuId: string
  productName: string
  mainImg: string
  userName: string
  rating: number
  content: string
  images: string[]
  tags: string[]
  status: number
  hideReason: string | null
  merchantReply: string | null
  createTime: DateTimeValue
}

export interface PageResult<T> {
  records: T[]
  total: number
  current: number
  size: number
}

/** GET /api/merchant/review/list —— 本店评价分页。 */
export async function listMerchantReviews(params: {
  pageNum?: number
  pageSize?: number
  rating?: number | null
  replied?: boolean | null
}): Promise<PageResult<MerchantReview>> {
  const res = await http.get('/merchant/review/list', {
    params: {
      pageNum: params.pageNum ?? 1,
      pageSize: params.pageSize ?? 10,
      ...(params.rating ? { rating: params.rating } : {}),
      ...(params.replied === null || params.replied === undefined ? {} : { replied: params.replied }),
    },
  })
  return res.data.data as PageResult<MerchantReview>
}

/** POST /api/merchant/review/{id}/reply —— 回复评价（仅一次）。 */
export async function replyReview(id: string, reply: string): Promise<void> {
  await http.post(`/merchant/review/${id}/reply`, { reply })
}

/** GET /api/admin/review/list —— 全平台评价分页。 */
export async function listAdminReviews(params: {
  pageNum?: number
  pageSize?: number
  shopId?: string | null
  rating?: number | null
  status?: number | null
  keyword?: string
}): Promise<PageResult<AdminReview>> {
  const res = await http.get('/admin/review/list', {
    params: {
      pageNum: params.pageNum ?? 1,
      pageSize: params.pageSize ?? 10,
      ...(params.shopId ? { shopId: params.shopId } : {}),
      ...(params.rating ? { rating: params.rating } : {}),
      ...(params.status === null || params.status === undefined ? {} : { status: params.status }),
      ...(params.keyword ? { keyword: params.keyword } : {}),
    },
  })
  return res.data.data as PageResult<AdminReview>
}

/** POST /api/admin/review/{id}/hide —— 隐藏评价（原因必填）。 */
export async function hideReview(id: string, reason: string): Promise<void> {
  await http.post(`/admin/review/${id}/hide`, { reason })
}

/** POST /api/admin/review/{id}/restore —— 恢复展示。 */
export async function restoreReview(id: string): Promise<void> {
  await http.post(`/admin/review/${id}/restore`)
}
