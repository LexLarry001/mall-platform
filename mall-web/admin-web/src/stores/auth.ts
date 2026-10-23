import { defineStore } from 'pinia'
import http from '../api/http'
import { buildMenuTree, collectMenuPaths } from '../utils/menu'
import type { MenuNode } from '../utils/menu'
import { visibleMenus } from '../utils/merchant-guard'
import {
  TOKEN_KEY,
  clearSession,
  readAuditStatus,
  readMenus,
  readToken,
  readUserInfo,
  writeAuditStatus,
  writeMenus,
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
    menus: readMenus<MenuNode>(),
    auditStatus: readAuditStatus(),
  }),
  getters: {
    menuTree(state): MenuNode[] {
      return visibleMenus(state.userInfo?.accountType ?? '', state.auditStatus, buildMenuTree(state.menus))
    },
    allowedPaths: (state): string[] => collectMenuPaths(state.menus),
  },
  actions: {
    async login(accountType: 'ADMIN' | 'MERCHANT', payload: LoginPayload): Promise<LoginResult> {
      const url = accountType === 'MERCHANT' ? '/merchant/auth/login' : '/admin/auth/login'
      const res = await http.post(url, payload)
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
      // 标记当前浏览器生效门户；买家端路由守卫据此让位
      setActivePortal(data.accountType === 'MERCHANT' ? 'merchant' : 'admin')
      if (data.accountType === 'MERCHANT') {
        this.menus = []
        writeMenus([])
        await this.loadApplyStatus()
      } else {
        this.auditStatus = null
        writeAuditStatus(null)
        await this.loadMenus()
      }
      return data
    },
    async loadMenus(): Promise<MenuNode[]> {
      const res = await http.get('/admin/auth/menus')
      this.menus = (res.data.data || []) as MenuNode[]
      writeMenus(this.menus)
      return this.menus
    },
    async loadApplyStatus(): Promise<number | null> {
      const res = await http.get('/merchant/apply/status')
      const data = res.data.data as { auditStatus: number | null }
      this.auditStatus = data?.auditStatus ?? null
      writeAuditStatus(this.auditStatus)
      return this.auditStatus
    },
    async logout(): Promise<void> {
      const portal = this.userInfo?.accountType === 'MERCHANT' ? 'merchant' : 'admin'
      try {
        await http.post('/auth/logout')
      } catch {
        // 登录态可能已失效，忽略即可
      }
      this.token = ''
      this.userInfo = null
      this.menus = []
      this.auditStatus = null
      clearSession()
      clearActivePortal(portal)
    },
  },
})
