import http from './http'
import type { Address } from './address'
import type { CartItem } from './cart'

/** 订单时间字段：Jackson 可能输出 ISO 字符串或 [y,m,d,h,mi,s] 数组。 */
export type DateTimeValue = string | number[] | null

/** 主订单状态（M6 新增 4 已退款 = 全部子订单退款成功）。 */
export const ORDER_STATUS: Record<number, string> = {
  0: '待支付',
  1: '已支付',
  2: '已完成',
  3: '已取消',
  4: '已退款',
}

/** 子订单状态（M6 新增 5 退款中 / 6 已退款 / 7 部分退款）。 */
export const SUB_ORDER_STATUS: Record<number, string> = {
  0: '待支付',
  1: '待发货',
  2: '待收货',
  3: '已完成',
  4: '已取消',
  5: '退款中',
  6: '已退款',
  7: '部分退款',
}

/** 结算页视图（与后端 SettleView 对齐）。 */
export interface SettleData {
  defaultAddress: Address | null
  items: CartItem[]
  totalAmount: number
}

/** 订单项视图（itemId 供退款申请定位）。 */
export interface OrderItem {
  itemId: number
  spuId: string
  skuId: string
  productName: string
  mainImg: string
  specJson: string
  price: number
  quantity: number
  /** M10：该订单项是否已发表评价。 */
  reviewed?: boolean
}

/** 订单列表摘要。 */
export interface OrderSummary {
  orderNo: string
  status: number
  totalAmount: number
  payAmount: number
  createTime: DateTimeValue
  expireTime: DateTimeValue
  items: OrderItem[]
}

/** 子订单视图（一店一单）。 */
export interface SubOrder {
  subOrderNo: string
  shopId: string
  shopName: string
  amount: number
  status: number
  items: OrderItem[]
}

/** 订单详情：主订单（含地址快照）+ 子订单 + 订单项三级聚合。 */
export interface OrderDetail {
  orderNo: string
  status: number
  totalAmount: number
  payAmount: number
  receiver: string
  phone: string
  fullAddress: string
  createTime: DateTimeValue
  expireTime: DateTimeValue
  subOrders: SubOrder[]
}

/** GET /api/user/order/settle —— 结算页数据（勾选+默认地址+试算）。 */
export async function getSettle(): Promise<SettleData> {
  const res = await http.get('/user/order/settle')
  return res.data.data as SettleData
}

/** POST /api/user/order —— 提交订单，返回主订单号。 */
export async function submitOrder(addressId: string): Promise<string> {
  const res = await http.post('/user/order', { addressId })
  return res.data.data as string
}

/** GET /api/user/order/list —— 我的订单列表（status 可选过滤）。 */
export async function listOrders(status?: number): Promise<OrderSummary[]> {
  const res = await http.get('/user/order/list', {
    params: status === undefined ? {} : { status },
  })
  return res.data.data as OrderSummary[]
}

/** GET /api/user/order/{orderNo} —— 订单详情。 */
export async function getOrderDetail(orderNo: string): Promise<OrderDetail> {
  const res = await http.get(`/user/order/${orderNo}`)
  return res.data.data as OrderDetail
}

/** POST /api/user/order/{orderNo}/cancel —— 取消订单（仅待支付）。 */
export async function cancelOrder(orderNo: string): Promise<void> {
  await http.post(`/user/order/${orderNo}/cancel`)
}
