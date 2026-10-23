import { describe, expect, it } from 'vitest'
import { extractRoute, hasRoute, latestPosition, pointAtRatio, statusRatio } from '../logisticsMap'
import type { LngLat, LogisticsTraceLike } from '../logisticsMap'

const POLYLINE: LngLat[] = [
  [121.0, 31.0],
  [121.05, 31.05],
  [121.1, 31.1],
]

function routeTrace(): LogisticsTraceLike {
  return { nodeType: 'ROUTE', status: 'SHIPPED', polyline: POLYLINE }
}

describe('logisticsMap', () => {
  it('extractRoute 提取 ROUTE 节点的折线', () => {
    const traces: LogisticsTraceLike[] = [
      { nodeType: 'STATUS', status: 'DELIVERED' },
      routeTrace(),
    ]

    expect(extractRoute(traces)).toEqual(POLYLINE)
    expect(hasRoute(traces)).toBe(true)
  })

  it('无 ROUTE 节点或有效点不足两个时返回 null', () => {
    expect(extractRoute([])).toBeNull()
    expect(extractRoute([{ nodeType: 'STATUS', status: 'SHIPPED' }])).toBeNull()
    expect(
      extractRoute([{ nodeType: 'ROUTE', polyline: [[121.0, 31.0]] }]),
    ).toBeNull()
    expect(hasRoute(undefined)).toBe(false)
  })

  it('pointAtRatio 与后端比例一致：0 / 50% / 85% / 100%', () => {
    expect(pointAtRatio(POLYLINE, 0)).toEqual([121.0, 31.0])

    const middle = pointAtRatio(POLYLINE, 0.5)!
    expect(middle[0]).toBeCloseTo(121.05, 6)
    expect(middle[1]).toBeCloseTo(31.05, 6)

    const nearEnd = pointAtRatio(POLYLINE, 0.85)!
    expect(nearEnd[0]).toBeCloseTo(121.085, 6)
    expect(nearEnd[1]).toBeCloseTo(31.085, 6)

    expect(pointAtRatio(POLYLINE, 1)).toEqual([121.1, 31.1])
    expect(pointAtRatio([], 0.5)).toBeNull()
  })

  it('latestPosition 取时间倒序中第一个带坐标的 STATUS 节点', () => {
    const traces: LogisticsTraceLike[] = [
      { nodeType: 'STATUS', status: 'IN_TRANSIT', lng: 121.05, lat: 31.05 },
      { nodeType: 'STATUS', status: 'SHIPPED', lng: 121.0, lat: 31.0 },
      routeTrace(),
    ]

    expect(latestPosition(traces)).toEqual([121.05, 31.05])
    expect(latestPosition([{ nodeType: 'ROUTE', polyline: POLYLINE }])).toBeNull()
  })

  it('statusRatio 映射四态，未知状态按 0', () => {
    expect(statusRatio('SHIPPED')).toBe(0)
    expect(statusRatio('IN_TRANSIT')).toBe(0.5)
    expect(statusRatio('OUT_FOR_DELIVERY')).toBe(0.85)
    expect(statusRatio('DELIVERED')).toBe(1)
    expect(statusRatio('EXCEPTION')).toBe(0)
    expect(statusRatio(null)).toBe(0)
  })
})
