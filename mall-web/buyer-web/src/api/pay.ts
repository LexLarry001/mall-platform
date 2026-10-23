import http from './http'

/**
 * 支付域 API（M4）。
 *
 * 链路：createPayForm 取表单 HTML → submitAlipayForm 跳沙箱收银台 →
 * 买家付款 → 支付宝 return_url 跳回 /pay/result → queryPayResult 主动查询兜底推进
 * （本地无公网收不到异步回调，query 兜底是本地演示链路的关键一环）。
 */

/** POST /api/user/pay/{orderNo} —— 发起支付，返回支付宝电脑网站支付表单 HTML。 */
export async function createPayForm(orderNo: string): Promise<string> {
  const res = await http.post(`/user/pay/${orderNo}`)
  return res.data.data as string
}

/** POST /api/user/pay/{orderNo}/query —— 主动查询支付结果（回调不可达时的兜底推进），返回订单当前 status。 */
export async function queryPayResult(orderNo: string): Promise<number> {
  const res = await http.post(`/user/pay/${orderNo}/query`)
  return res.data.data as number
}

/**
 * 跳转支付宝收银台：表单 HTML 注入隐藏 div 后手动 form.submit()。
 * innerHTML 不会执行内联 <script>（SDK 表单含 script 自动提交），必须手动提交。
 */
export function submitAlipayForm(formHtml: string): void {
  const container = document.createElement('div')
  container.style.display = 'none'
  container.innerHTML = formHtml
  document.body.appendChild(container)
  const form = container.querySelector('form')
  if (!form) {
    document.body.removeChild(container)
    throw new Error('支付表单异常，请稍后重试')
  }
  form.submit()
}
