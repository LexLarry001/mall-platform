import http from './http'
import type { DateTimeValue } from './order'

/** 退款单状态：0 待审核 / 2 已退款 / 3 已驳回 / 4 退款失败。 */
export const REFUND_STATUS: Record<number, string> = {
  0: '待审核',
  2: '已退款',
  3: '已驳回',
  4: '退款失败',
}

/** 退款原因字典（前端固定，后端只存字符串）。 */
export const REFUND_REASONS = [
  '不想要了',
  '商品质量问题',
  '商品与描述不符',
  '少件漏发',
  '其它',
]

/** 退款单视图（与后端 RefundView 对齐）。 */
export interface RefundInfo {
  refundNo: string
  orderNo: string
  subOrderNo: string
  orderItemId: number
  spuId: string
  skuId: string
  productName: string
  shopId: string
  quantity: number
  refundAmount: number
  reason: string
  evidenceImages: string[]
  status: number
  auditRemark: string | null
  auditTime: DateTimeValue
  refundTime: DateTimeValue
  failMsg: string | null
  createTime: DateTimeValue
}

export interface RefundApplyPayload {
  orderNo: string
  subOrderNo: string
  orderItemId: number
  reason: string
  evidenceImages: string[]
}

/** POST /api/user/refund —— 申请退款（订单项整项退），返回退款单号。 */
export async function applyRefund(payload: RefundApplyPayload): Promise<string> {
  const res = await http.post('/user/refund', payload)
  return res.data.data as string
}

/** GET /api/user/refund/order/{orderNo} —— 某主订单下的退款单列表。 */
export async function listRefundsByOrder(orderNo: string): Promise<RefundInfo[]> {
  const res = await http.get(`/user/refund/order/${orderNo}`)
  return res.data.data as RefundInfo[]
}

/** POST /api/file/upload —— 上传退款凭证，返回 /static/** URL。 */
export async function uploadEvidence(file: File): Promise<string> {
  const fd = new FormData()
  fd.append('file', file)
  const res = await http.post('/file/upload', fd, {
    headers: { 'Content-Type': 'multipart/form-data' },
  })
  return (res.data.data as { url: string }).url
}
