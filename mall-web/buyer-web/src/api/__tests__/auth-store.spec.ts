import { describe, it, expect, beforeEach, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'

vi.mock('../http', () => ({
  default: { post: vi.fn() },
}))

import http from '../http'
import { useAuthStore } from '../../stores/auth'

describe('buyer auth store', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    localStorage.clear()
    vi.clearAllMocks()
  })

  it('登录成功后 token 有值且已持久化到 localStorage', async () => {
    ;(http.post as any).mockResolvedValue({
      data: {
        code: 0,
        message: 'success',
        data: { accessToken: 'tk-access', refreshToken: 'tk-refresh', accountId: 1001, accountType: 'USER', shopId: null },
      },
    })

    const store = useAuthStore()
    await store.login({ username: 'buyer01', password: 'buyer123', captchaKey: 'k1', captchaCode: '1234' })

    expect(store.token).toBe('tk-access')
    expect(localStorage.getItem('mall_token')).toBe('tk-access')
    expect(store.userInfo?.accountId).toBe(1001)
  })

  it('退出后清空 token 与 localStorage', async () => {
    ;(http.post as any).mockResolvedValue({ data: { code: 0, message: 'success', data: null } })

    const store = useAuthStore()
    store.token = 'tk-access'
    localStorage.setItem('mall_token', 'tk-access')

    await store.logout()

    expect(store.token).toBe('')
    expect(store.userInfo).toBeNull()
    expect(localStorage.getItem('mall_token')).toBeNull()
  })
})
