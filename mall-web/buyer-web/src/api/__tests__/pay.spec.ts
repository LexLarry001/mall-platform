import { describe, it, expect, beforeEach, vi } from 'vitest'

vi.mock('../http', () => ({
  default: { post: vi.fn() },
}))

import http from '../http'
import { createPayForm, queryPayResult, submitAlipayForm } from '../pay'

describe('pay api', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    document.body.innerHTML = ''
  })

  it('createPayForm 调用后端并透传表单 HTML', async () => {
    ;(http.post as any).mockResolvedValue({
      data: { code: 0, message: 'success', data: '<form action="https://openapi-sandbox.dl.alipaydev.com/gateway.do">x</form>' },
    })

    const form = await createPayForm('M123')

    expect(http.post).toHaveBeenCalledWith('/user/pay/M123')
    expect(form).toContain('openapi-sandbox')
  })

  it('queryPayResult 返回订单当前 status', async () => {
    ;(http.post as any).mockResolvedValue({
      data: { code: 0, message: 'success', data: 1 },
    })

    const status = await queryPayResult('M123')

    expect(http.post).toHaveBeenCalledWith('/user/pay/M123/query')
    expect(status).toBe(1)
  })

  it('submitAlipayForm 注入隐藏容器并手动提交表单', () => {
    const submitSpy = vi
      .spyOn(HTMLFormElement.prototype, 'submit')
      .mockImplementation(() => {})

    submitAlipayForm('<form action="https://example.com/pay"><input name="a" value="1"></form>')

    const container = document.body.querySelector('div')
    expect(container).not.toBeNull()
    expect(container!.style.display).toBe('none')
    expect(container!.querySelector('form')).not.toBeNull()
    expect(submitSpy).toHaveBeenCalledTimes(1)
    submitSpy.mockRestore()
  })

  it('submitAlipayForm 无 form 标签时清理容器并报错', () => {
    expect(() => submitAlipayForm('<div>not a form</div>')).toThrow('支付表单异常，请稍后重试')
    expect(document.body.querySelector('div')).toBeNull()
  })
})
