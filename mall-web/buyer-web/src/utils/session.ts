export const TOKEN_KEY = 'mall_token'
export const USER_KEY = 'mall_user'
export const REFRESH_KEY = 'mall_refresh_token'

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

export function readRefreshToken(): string {
  return sessionStorage.getItem(REFRESH_KEY) || ''
}

export function clearSession(): void {
  localStorage.removeItem(TOKEN_KEY)
  localStorage.removeItem(USER_KEY)
  sessionStorage.removeItem(REFRESH_KEY)
}
