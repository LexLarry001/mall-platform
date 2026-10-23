import { defineStore } from 'pinia'
import http from '../api/http'
import {
  TOKEN_KEY,
  clearSession,
  readToken,
  readUserInfo,
  writeRefreshToken,
  writeUserInfo,
} from '../utils/session'
import { clearActivePortal, setActivePortal } from '../utils/portal'

export interface LoginPayload {
  username: string
  password: string
  captchaKey: string
  captchaCode: string
}

export interface RegisterPayload extends LoginPayload {}

export interface LoginResult {
  accessToken: string
  refreshToken: string
  accountId: number
  accountType: string
  shopId: number | null
}

export interface UserInfo {
  accountId: number
  username: string
  accountType: string
}

export const useAuthStore = defineStore('auth', {
  state: () => ({
    token: readToken(),
    userInfo: readUserInfo<UserInfo>(),
  }),
  actions: {
    async login(payload: LoginPayload): Promise<LoginResult> {
      const res = await http.post('/user/auth/login', payload)
      const data = res.data.data as LoginResult
      this.token = data.accessToken
      this.userInfo = {
        accountId: data.accountId,
        username: payload.username,
        accountType: data.accountType,
      }
      localStorage.setItem(TOKEN_KEY, this.token)
      writeUserInfo(this.userInfo)
      writeRefreshToken(data.refreshToken)
      // 标记当前浏览器生效门户为买家端，商家/平台工作台据此让位
      setActivePortal('buyer')
      return data
    },
    async register(payload: RegisterPayload): Promise<void> {
      await http.post('/user/auth/register', payload)
    },
    async logout(): Promise<void> {
      try {
        await http.post('/auth/logout')
      } catch {
        // 登录态可能已失效，忽略即可
      }
      this.token = ''
      this.userInfo = null
      clearSession()
      clearActivePortal('buyer')
    },
  },
})
