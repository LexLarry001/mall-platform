export const TOKEN_KEY = 'mall_token'
export const USER_KEY = 'mall_user'
export const REFRESH_KEY = 'mall_refresh_token'
export const MENUS_KEY = 'mall_admin_menus'

export function readToken(): string {
  return localStorage.getItem(TOKEN_KEY) || ''
}

export function readUserInfo<T>(): T | null {
  const raw = localStorage.getItem(USER_KEY)
  if (!raw) return null
  try {
    return JSON.parse(raw) as T
  } catch {
    return null
  }
}

export function writeUserInfo(value: unknown): void {
  localStorage.setItem(USER_KEY, JSON.stringify(value))
}

export function writeRefreshToken(token: string): void {
  sessionStorage.setItem(REFRESH_KEY, token)
}

export function readMenus<T>(): T[] {
  const raw = localStorage.getItem(MENUS_KEY)
  if (!raw) return []
  try {
    return JSON.parse(raw) as T[]
  } catch {
    return []
  }
}

export function writeMenus(value: unknown): void {
  localStorage.setItem(MENUS_KEY, JSON.stringify(value))
}

const AUDIT_STATUS_KEY = 'mall_merchant_audit_status'

export function readAuditStatus(): number | null {
  const raw = localStorage.getItem(AUDIT_STATUS_KEY)
  if (raw === null || raw === '') return null
  const value = Number(raw)
  return Number.isNaN(value) ? null : value
}

export function writeAuditStatus(value: number | null): void {
  if (value === null || value === undefined) {
    localStorage.removeItem(AUDIT_STATUS_KEY)
  } else {
    localStorage.setItem(AUDIT_STATUS_KEY, String(value))
  }
}

export function clearSession(): void {
  localStorage.removeItem(TOKEN_KEY)
  localStorage.removeItem(USER_KEY)
  localStorage.removeItem(MENUS_KEY)
  localStorage.removeItem(AUDIT_STATUS_KEY)
  sessionStorage.removeItem(REFRESH_KEY)
}
