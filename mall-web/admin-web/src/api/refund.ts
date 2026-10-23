import http from './http'

/** 退款单状态：0 待审核 / 2 已退款 / 3 已驳回 / 4 退款失败。 */
export const REFUND_STATUS: Record<number, string> = {
  0: '待审核',
  2: '已退款',
  3: '已驳回',
  4: '退款失败',
}

/** 退款单视图（与后端 RefundView 对齐）。 */
export interface RefundOrderView {
  refundNo: string
  orderNo: string
  subOrderNo: string
  orderItemId: number
  spuId: number
  skuId: number
  productName: string
  shopId: number
  quantity: number
  refundAmount: number
  reason: string
  evidenceImages: string[]
  status: number
  auditRemark: string | null
  auditTime: string | number[] | null
  refundTime: string | number[] | null
  failMsg: string | null
  createTime: string | number[] | null
}

/** GET /api/merchant/refund/list —— 本店退款单列表（status 可选）。 */
export async function listMerchantRefunds(status?: number): Promise<RefundOrderView[]> {
  const res = await http.get('/merchant/refund/list', {
    params: status === undefined ? {} : { status },
  })
  return res.data.data as RefundOrderView[]
}

/** POST /api/merchant/refund/{refundNo}/approve —— 同意退款（同步调沙箱）。 */
export async function approveRefund(refundNo: string, remark?: string): Promise<void> {
  await http.post(`/merchant/refund/${refundNo}/approve`, { remark: remark ?? '' })
}

/** POST /api/merchant/refund/{refundNo}/reject —— 驳回退款。 */
export async function rejectRefund(refundNo: string, remark: string): Promise<void> {
  await http.post(`/merchant/refund/${refundNo}/reject`, { remark })
}

/** POST /api/merchant/refund/{refundNo}/retry —— 退款失败重试。 */
export async function retryRefund(refundNo: string): Promise<void> {
  await http.post(`/merchant/refund/${refundNo}/retry`)
}
