import http from './http'

/** 发货单（MySQL shipment）。 */
export interface ShipmentInfo {
  shipmentNo: string
  subOrderNo: string
  provider: string
  carrierCode: string
  carrierName: string
  trackingNo: string
  /** 发货地址（M7 商家填写，高德地理编码源）。 */
  fromAddress: string | null
  /** 收货地址（M7 主订单快照）。 */
  toAddress: string | null
  fromLng: number | null
  fromLat: number | null
  toLng: number | null
  toLat: number | null
  currentStatus: string
  traceCount: number
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

/** 物流状态文案。 */
export const LOGISTICS_STATUS: Record<string, string> = {
  INIT: '待发货',
  SHIPPED: '已发货',
  IN_TRANSIT: '运输中',
  OUT_FOR_DELIVERY: '派送中',
  DELIVERED: '已签收',
  EXCEPTION: '异常',
}

/** GET /api/user/logistics/shipment/{subOrderNo} —— 子订单发货单（仅本人）。 */
export async function getShipment(subOrderNo: string): Promise<ShipmentInfo | null> {
  try {
    const res = await http.get(`/user/logistics/shipment/${subOrderNo}`)
    return res.data.data as ShipmentInfo
  } catch {
    return null
  }
}

/** GET /api/user/logistics/trace/{shipmentNo} —— 物流轨迹（时间倒序）。 */
export async function getTrace(shipmentNo: string): Promise<LogisticsTrace[]> {
  const res = await http.get(`/user/logistics/trace/${shipmentNo}`)
  return res.data.data as LogisticsTrace[]
}

/** POST /api/user/logistics/confirm —— 确认收货。 */
export async function confirmReceive(orderNo: string, subOrderNo: string): Promise<void> {
  await http.post('/user/logistics/confirm', null, { params: { orderNo, subOrderNo } })
}
