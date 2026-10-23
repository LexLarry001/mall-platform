/**
 * 高德物流地图纯函数工具：路线提取 / 状态行程比例 / 折线插值 / 当前位置。
 *
 * <p>比例与后端 AmapLogisticsProvider 完全一致：SHIPPED=0、IN_TRANSIT=50%、
 * OUT_FOR_DELIVERY=85%、DELIVERED=100%，保证前后端同一时刻显示同一点。</p>
 */
export type LngLat = [number, number]

/** 轨迹节点最小结构（buyer/admin 的 LogisticsTrace 均兼容）。 */
export interface LogisticsTraceLike {
  nodeType?: string | null
  status?: string | null
  lng?: number | string | null
  lat?: number | string | null
  polyline?: Array<Array<number | string>> | null
}

/** 物流状态 → 行程比例（与后端一致）。 */
export const STATUS_RATIO: Record<string, number> = {
  SHIPPED: 0,
  IN_TRANSIT: 0.5,
  OUT_FOR_DELIVERY: 0.85,
  DELIVERED: 1,
}

function toPoint(raw: unknown): LngLat | null {
  if (!Array.isArray(raw) || raw.length < 2) return null
  const lng = Number(raw[0])
  const lat = Number(raw[1])
  if (!Number.isFinite(lng) || !Number.isFinite(lat)) return null
  return [lng, lat]
}

function distance(a: LngLat, b: LngLat): number {
  const dx = b[0] - a[0]
  const dy = b[1] - a[1]
  return Math.sqrt(dx * dx + dy * dy)
}

/** 提取 ROUTE 节点折线；无路线或有效点不足 2 个返回 null。 */
export function extractRoute(traces: LogisticsTraceLike[] | null | undefined): LngLat[] | null {
  const node = (traces ?? []).find((t) => t.nodeType === 'ROUTE' && Array.isArray(t.polyline))
  if (!node?.polyline) return null
  const points = node.polyline.map(toPoint).filter((p): p is LngLat => p !== null)
  return points.length >= 2 ? points : null
}

/** 是否具备可渲染的路线（组件用来决定显示地图还是文字降级提示）。 */
export function hasRoute(traces: LogisticsTraceLike[] | null | undefined): boolean {
  return extractRoute(traces) !== null
}

/** 状态 → 行程比例；未知状态按 0 处理。 */
export function statusRatio(status: string | null | undefined): number {
  return STATUS_RATIO[status ?? ''] ?? 0
}

/** 按累计长度比例取折线上的点（0~1）。 */
export function pointAtRatio(
  polyline: LngLat[] | null | undefined,
  ratio: number,
): LngLat | null {
  if (!polyline || polyline.length === 0) return null
  if (polyline.length === 1) return polyline[0]

  const clamped = Math.min(1, Math.max(0, Number.isFinite(ratio) ? ratio : 0))
  const segments: number[] = []
  let total = 0
  for (let i = 1; i < polyline.length; i++) {
    const d = distance(polyline[i - 1], polyline[i])
    segments.push(d)
    total += d
  }
  if (total <= 0) return polyline[0]

  const target = total * clamped
  let accumulated = 0
  for (let i = 0; i < segments.length; i++) {
    if (accumulated + segments[i] >= target) {
      const segRatio = segments[i] <= 0 ? 0 : (target - accumulated) / segments[i]
      const [x1, y1] = polyline[i]
      const [x2, y2] = polyline[i + 1]
      return [x1 + (x2 - x1) * segRatio, y1 + (y2 - y1) * segRatio]
    }
    accumulated += segments[i]
  }
  return polyline[polyline.length - 1]
}

/** 最新 STATUS 节点的坐标（traces 时间倒序）；没有坐标节点返回 null。 */
export function latestPosition(
  traces: LogisticsTraceLike[] | null | undefined,
): LngLat | null {
  for (const trace of traces ?? []) {
    if (trace.nodeType !== 'STATUS') continue
    const point = toPoint([trace.lng, trace.lat])
    if (point) return point
  }
  return null
}
