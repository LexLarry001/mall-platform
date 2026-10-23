import { describe, it, expect, beforeEach, vi } from 'vitest'

vi.mock('../http', () => ({
  default: { get: vi.fn(), post: vi.fn() },
}))

import http from '../http'
import { applyRefund, listRefundsByOrder, REFUND_STATUS, uploadEvidence } from '../refund'

describe('refund api', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('applyRefund 提交订单项粒度退款申请并返回退款单号', async () => {
    ;(http.post as any).mockResolvedValue({ data: { code: 0, message: 'success', data: 'RF1001' } })

    const refundNo = await applyRefund({
      orderNo: 'M1',
      subOrderNo: 'S1',
      orderItemId: 9,
      reason: '商品质量问题',
      evidenceImages: ['/static/a.jpg'],
    })

    expect(http.post).toHaveBeenCalledWith('/user/refund', {
      orderNo: 'M1',
      subOrderNo: 'S1',
      orderItemId: 9,
      reason: '商品质量问题',
      evidenceImages: ['/static/a.jpg'],
    })
    expect(refundNo).toBe('RF1001')
  })

  it('listRefundsByOrder 按主订单号查询退款单列表', async () => {
    ;(http.get as any).mockResolvedValue({
      data: { code: 0, message: 'success', data: [{ refundNo: 'RF1', status: 0 }] },
    })

    const list = await listRefundsByOrder('M1')

    expect(http.get).toHaveBeenCalledWith('/user/refund/order/M1')
    expect(list).toHaveLength(1)
    expect(list[0].refundNo).toBe('RF1')
  })

  it('uploadEvidence 使用 multipart 上传并返回静态 URL', async () => {
    ;(http.post as any).mockResolvedValue({ data: { code: 0, message: 'success', data: { url: '/static/e.jpg' } } })

    const url = await uploadEvidence(new File(['x'], 'e.jpg', { type: 'image/jpeg' }))

    expect(http.post).toHaveBeenCalledWith('/file/upload', expect.any(FormData), {
      headers: { 'Content-Type': 'multipart/form-data' },
    })
    expect(url).toBe('/static/e.jpg')
  })

  it('退款状态文案覆盖四种状态', () => {
    expect(REFUND_STATUS[0]).toBe('待审核')
    expect(REFUND_STATUS[2]).toBe('已退款')
    expect(REFUND_STATUS[3]).toBe('已驳回')
    expect(REFUND_STATUS[4]).toBe('退款失败')
  })
})
