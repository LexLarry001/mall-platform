import http from './http'

/** 商家订单项（商品快照）。 */
export interface MerchantOrderItem {
  spuId: number
  skuId: number
  productName: string
  mainImg: string
  specJson: string
  price: number
  quantity: number
}

/** 商家子订单视图（含买家快照与发货单状态）。 */
export interface MerchantOrder {
  subOrderNo: string
  orderNo: string | null
  status: number
  amount: number
  createTime: string | number[] | null
  receiver: string | null
  phone: string | null
  fullAddress: string | null
  items: MerchantOrderItem[]
  shipmentNo: string | null
  carrierName: string | null
  trackingNo: string | null
  logisticsStatus: string | null
  traceCount: number | null
}

/** 物流轨迹节点（MongoDB logistics_trace）。 */
export interface LogisticsTrace {
  id: string
  shipmentNo: string
  nodeType: string
  status: string
  desc: string
  address: string | null
  lng: number | null
  lat: number | null
  /** ROUTE 节点的路线折线 [[lng, lat], ...]。 */
  polyline: number[][] | null
  ts: string | number[] | null
}

/** 子订单状态文案（M6 新增 5 退款中 / 6 已退款 / 7 部分退款）。 */
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

/** 物流状态文案。 */
export const LOGISTICS_STATUS: Record<string, string> = {
  INIT: '待发货',
  SHIPPED: '已发货',
  IN_TRANSIT: '运输中',
  OUT_FOR_DELIVERY: '派送中',
  DELIVERED: '已签收',
  EXCEPTION: '异常',
}

/** 物流状态推进顺序（下一位即按钮展示的下一状态）。 */
export const LOGISTICS_NEXT: Record<string, string | null> = {
  SHIPPED: 'IN_TRANSIT',
  IN_TRANSIT: 'OUT_FOR_DELIVERY',
  OUT_FOR_DELIVERY: 'DELIVERED',
  DELIVERED: null,
}

/** 承运商字典（硬编码常用几家，后续可换字典表）。 */
export const CARRIERS = [
  { code: 'YTO', name: '圆通' },
  { code: 'ZTO', name: '中通' },
  { code: 'STO', name: '申通' },
  { code: 'YUNDA', name: '韵达' },
  { code: 'EMS', name: 'EMS' },
]

export interface ShipPayload {
  carrierCode: string
  carrierName: string
  trackingNo: string
  /** 发货地址（M7 高德地理编码源，amap 模式必填）。 */
  fromAddress: string
}

/** GET /api/merchant/order/list —— 本店子订单列表。 */
export async function listMerchantOrders(status?: number): Promise<MerchantOrder[]> {
  const res = await http.get('/merchant/order/list', {
    params: status === undefined ? {} : { status },
  })
  return res.data.data as MerchantOrder[]
}

/** POST /api/merchant/ship/{subOrderNo} —— 发货，返回发货单号。 */
export async function shipOrder(subOrderNo: string, payload: ShipPayload): Promise<string> {
  const res = await http.post(`/merchant/ship/${subOrderNo}`, payload)
  return res.data.data as string
}

/** POST /api/merchant/ship/progress/{shipmentNo} —— 推进物流，返回下一状态。 */
export async function pushLogistics(shipmentNo: string, desc: string): Promise<string> {
  const res = await http.post(`/merchant/ship/progress/${shipmentNo}`, { desc })
  return res.data.data as string
}

/** GET /api/merchant/ship/{subOrderNo} —— 查本店发货单。 */
export async function getShipment(subOrderNo: string): Promise<unknown> {
  const res = await http.get(`/merchant/ship/${subOrderNo}`)
  return res.data.data
}

/** 轨迹查询走买家接口前需登录态为 USER；商家端通过物流单号查轨迹用同一路径。 */
export async function getMerchantTrace(shipmentNo: string): Promise<LogisticsTrace[]> {
  const res = await http.get(`/merchant/ship/trace/${shipmentNo}`)
  return res.data.data as LogisticsTrace[]
}
