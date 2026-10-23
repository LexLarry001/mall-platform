import http from './http'
import type { DateTimeValue } from './order'

/** 公开评价列表项（匿名评价的 userName 已脱敏）。 */
export interface ReviewItem {
  id: string
  userName: string
  rating: number
  content: string
  images: string[]
  tags: string[]
  merchantReply: string | null
  replyTime: DateTimeValue
  createTime: DateTimeValue
}

/** 公开评价分页（ratingStat：星级 → 条数）。 */
export interface ReviewPage {
  records: ReviewItem[]
  total: number
  ratingStat: Record<string, number>
}

/** AI 评价摘要（source=ai 为模型生成，stat 为统计降级）。 */
export interface ReviewSummary {
  spuId: string
  reviewCount: number
  avgRating: number
  recommendRate: number
  summary: string
  pros: string[]
  cons: string[]
  tags: string[]
  source: string
  updatedAt: DateTimeValue
}

export interface ReviewItemPayload {
  orderItemId: number
  rating: number
  content: string
  images: string[]
  anonymous: boolean
}

/** POST /api/user/review —— 对已完成订单项发表评价，返回评价 ID。 */
export async function submitReview(payload: ReviewItemPayload): Promise<string> {
  const res = await http.post('/user/review', payload)
  return res.data.data as string
}

/** GET /api/product/{spuId}/reviews —— 公开评价分页。 */
export async function listProductReviews(
  spuId: string,
  params: { pageNum?: number; pageSize?: number; rating?: number; hasImage?: boolean; tag?: string } = {},
): Promise<ReviewPage> {
  const res = await http.get(`/product/${spuId}/reviews`, { params })
  return res.data.data as ReviewPage
}

/** GET /api/product/{spuId}/review-summary —— AI 评价摘要。 */
export async function getReviewSummary(spuId: string): Promise<ReviewSummary> {
  const res = await http.get(`/product/${spuId}/review-summary`)
  return res.data.data as ReviewSummary
}

/** POST /api/file/upload —— 上传评价图片，返回 /static/** URL。 */
export async function uploadReviewImage(file: File): Promise<string> {
  const fd = new FormData()
  fd.append('file', file)
  const res = await http.post('/file/upload', fd, {
    headers: { 'Content-Type': 'multipart/form-data' },
  })
  return (res.data.data as { url: string }).url
}

export interface RecommendPayload {
  query: string
  budgetMax?: number | null
  categoryId?: number | string | null
  limit?: number
}

export interface RecommendItem {
  spuId: string
  name: string
  mainImg: string
  price: number
  avgRating: number
  commentCount: number
  score: number
  reason: string
  evidence: string[]
}

export interface RecommendResult {
  source: string
  parsed: { categoryId: string | null; budgetMax: number | null; keywords: string[] } | null
  summary: string
  items: RecommendItem[]
}

/** POST /api/user/ai/recommend —— AI 客服按真实评价推荐商品。 */
export async function aiRecommend(payload: RecommendPayload): Promise<RecommendResult> {
  const res = await http.post('/user/ai/recommend', payload, { timeout: 40000 })
  return res.data.data as RecommendResult
}
