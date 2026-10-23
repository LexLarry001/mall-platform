/**
 * 商品详情页规格选择纯函数：SKU 匹配与规格可用性矩阵。
 * 独立于 Vue，便于 Vitest 直接钉死行为。
 */

export interface SkuLike {
  id: number | string
  /** 规格组合，如 {颜色:'曜石黑', 尺码:'S'}（后端已解析为键值对）。 */
  specValues: Record<string, string>
  price: number | string
  stock: number
  skuImg?: string | null
}

export interface SpecDefLike {
  specName: string
  values: string[]
}

export interface SpecValueState {
  value: string
  /** true = 当前已选组合下无对应 SKU（或任何 SKU 都不含该值），不可选。 */
  disabled: boolean
}

/**
 * 按已选规格匹配 SKU：必须选齐全部规格维度才命中（部分选择返回 null）。
 * 库存为 0 的 SKU 仍可命中（由页面展示"库存 0"并禁止加购）。
 */
export function matchSku<T extends SkuLike>(skus: T[], selectedSpec: Record<string, string>): T | null {
  if (skus.length === 0) return null
  const requiredKeys = Object.keys(skus[0].specValues)
  if (!requiredKeys.every((key) => !!selectedSpec[key])) return null
  return skus.find((sku) => requiredKeys.every((key) => sku.specValues[key] === selectedSpec[key])) ?? null
}

/**
 * 规格值可用性矩阵：对每个规格维度，试选某值后与当前已选（其余维度）组合，
 * 存在至少一条 SKU 即视为可选。selected 中当前维度的旧值会被试选值覆盖。
 */
export function buildSpecMatrix(
  specs: SpecDefLike[],
  skus: SkuLike[],
  selected: Record<string, string>,
): Record<string, SpecValueState[]> {
  const matrix: Record<string, SpecValueState[]> = {}
  for (const spec of specs) {
    matrix[spec.specName] = spec.values.map((value) => {
      const trial = { ...selected, [spec.specName]: value }
      const hit = skus.some((sku) =>
        Object.entries(trial).every(([key, v]) => !v || sku.specValues[key] === v),
      )
      return { value, disabled: !hit }
    })
  }
  return matrix
}
