/**
 * 浏览器"当前生效门户"标记。
 *
 * 买家端（5173）与商家/平台工作台（5174）同主机不同端口，localStorage 互不相通，
 * 但 Cookie 不区分端口。任一门户登录时写入该 Cookie，另一门户在路由守卫里读取：
 * 发现当前浏览器已切换到对端门户，就清理本地登录态并回登录页，
 * 避免"买家登录后按浏览器后退仍回到商家工作台"的跨身份串页。
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
