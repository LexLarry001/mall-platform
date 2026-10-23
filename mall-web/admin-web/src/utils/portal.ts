/**
 * 浏览器"当前生效门户"标记（与 buyer-web 同名同值，Cookie 跨端口共享）。
 *
 * 商家/平台工作台启动时若发现 Cookie 已被买家端写成 buyer，
 * 说明当前浏览器已登录买家账号：主动清理工作台登录态并回登录页，
 * 避免按浏览器后退返回商家工作台造成身份串页。
 */
export type ActivePortal = 'buyer' | 'merchant' | 'admin'

const PORTAL_COOKIE = 'mall_active_portal'
const ONE_YEAR_SECONDS = 60 * 60 * 24 * 365

export function setActivePortal(portal: ActivePortal): void {
  document.cookie = `${PORTAL_COOKIE}=${portal}; path=/; max-age=${ONE_YEAR_SECONDS}; SameSite=Lax`
}

export function readActivePortal(): ActivePortal | '' {
  const hit = document.cookie
    .split(';')
    .map((part) => part.trim())
    .find((part) => part.startsWith(`${PORTAL_COOKIE}=`))
  if (!hit) return ''
  const value = hit.slice(PORTAL_COOKIE.length + 1)
  return value === 'buyer' || value === 'merchant' || value === 'admin' ? value : ''
}

export function clearActivePortal(portal: ActivePortal): void {
  if (readActivePortal() !== portal) return
  document.cookie = `${PORTAL_COOKIE}=; path=/; max-age=0; SameSite=Lax`
}
