/**
 * 购物车纯函数工具：按店铺分组 / 可结算过滤 / 勾选合计 / 全选判定。
 * 与后端 CartItemView 对齐的轻量结构（雪花 ID 序列化为字符串）。
 */
export interface CartItemLike {
  skuId: string
  spuId: string
  shopId: string
  shopName: string
  productName: string
  skuImg: string
  specJson: string
  price: number
  stock: number
  quantity: number
  checked: boolean
  invalid: boolean
}

export interface CartGroup {
  shopId: string
  shopName: string
  items: CartItemLike[]
}

/** 按店铺分组：组间顺序 = 店铺首次出现顺序，组内保持原顺序（与后端拆单语义一致）。 */
export function groupByShop(items: CartItemLike[]): CartGroup[] {
  const map = new Map<string, CartGroup>()
  for (const it of items) {
    let group = map.get(it.shopId)
    if (!group) {
      group = { shopId: it.shopId, shopName: it.shopName, items: [] }
      map.set(it.shopId, group)
    }
    group.items.push(it)
  }
  return [...map.values()]
}

/** 可结算条目：勾选且未失效（失效=下架/删除/库存 0，仅打标不自动删）。 */
export function settleableItems(items: CartItemLike[]): CartItemLike[] {
  return items.filter((i) => i.checked && !i.invalid)
}

/** 勾选合计（仅可结算条目）。以"分"累加规避浮点误差，返回元。 */
export function sumChecked(items: CartItemLike[]): number {
  let cents = 0
  for (const i of settleableItems(items)) {
    cents += Math.round(Number(i.price) * 100) * i.quantity
  }
  return cents / 100
}

/** 全选判定：存在有效条目且全部勾选；无有效条目（空/全失效）为 false。 */
export function allChecked(items: CartItemLike[]): boolean {
  const valid = items.filter((i) => !i.invalid)
  return valid.length > 0 && valid.every((i) => i.checked)
}

/**
 * 订单时间格式化：后端 LocalDateTime 依 Jackson 配置可能是 ISO 字符串或 [y,m,d,...] 数组。
 * 输出 `YYYY-MM-DD HH:mm:ss`；空值返回 '-'。
 */
export function formatDateTime(value: string | number[] | null | undefined): string {
  if (!value) return '-'
  if (typeof value === 'string') {
    return value.replace('T', ' ').slice(0, 19)
  }
  if (Array.isArray(value) && value.length >= 3) {
    const [y, m, d, hh = 0, mi = 0, ss = 0] = value
    const pad = (n: number) => String(n).padStart(2, '0')
    return `${y}-${pad(m)}-${pad(d)} ${pad(hh)}:${pad(mi)}:${pad(ss)}`
  }
  return '-'
}
