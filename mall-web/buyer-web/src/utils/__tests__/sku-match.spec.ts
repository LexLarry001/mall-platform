import { describe, expect, it } from 'vitest'
import { buildSpecMatrix, matchSku } from '../sku-match'
import type { SkuLike } from '../sku-match'

const skus: SkuLike[] = [
  { id: '1', specValues: { 颜色: '曜石黑', 尺码: 'S' }, price: 19.9, stock: 100, skuImg: null },
  { id: '2', specValues: { 颜色: '月牙白', 尺码: 'M' }, price: 29.9, stock: 0, skuImg: null },
]

const specs = [
  { specName: '颜色', values: ['曜石黑', '月牙白'] },
  { specName: '尺码', values: ['S', 'M', 'X'] },
]

describe('matchSku', () => {
  it('部分规格未选时返回 null', () => {
    expect(matchSku(skus, { 颜色: '曜石黑' })).toBeNull()
    expect(matchSku(skus, {})).toBeNull()
  })

  it('规格全选命中正确 SKU（价格库存联动）', () => {
    const hit = matchSku(skus, { 颜色: '曜石黑', 尺码: 'S' })
    expect(hit).not.toBeNull()
    expect(hit?.id).toBe('1')
    expect(Number(hit?.price)).toBe(19.9)
    expect(hit?.stock).toBe(100)
  })

  it('无库存 SKU 仍可选中，库存显示 0', () => {
    const hit = matchSku(skus, { 颜色: '月牙白', 尺码: 'M' })
    expect(hit).not.toBeNull()
    expect(hit?.id).toBe('2')
    expect(hit?.stock).toBe(0)
  })

  it('规格组合不存在时返回 null', () => {
    expect(matchSku(skus, { 颜色: '曜石黑', 尺码: 'M' })).toBeNull()
  })

  it('空 SKU 列表返回 null', () => {
    expect(matchSku([], { 颜色: '曜石黑' })).toBeNull()
  })
})

describe('buildSpecMatrix', () => {
  it('无已选时：存在于 SKU 的值可选，不存在于任何 SKU 的值禁用', () => {
    const matrix = buildSpecMatrix(specs, skus, {})
    expect(matrix['颜色'].map((v) => [v.value, v.disabled])).toEqual([
      ['曜石黑', false],
      ['月牙白', false],
    ])
    // X 未出现在任何 SKU 中 → 禁用
    expect(matrix['尺码'].map((v) => [v.value, v.disabled])).toEqual([
      ['S', false],
      ['M', false],
      ['X', true],
    ])
  })

  it('联动已选：与当前组合无 SKU 的值禁用', () => {
    const matrix = buildSpecMatrix(specs, skus, { 颜色: '曜石黑' })
    // 曜石黑 只有 S 组合 → M 禁用
    const sizeStates = matrix['尺码']
    expect(sizeStates.find((v) => v.value === 'S')?.disabled).toBe(false)
    expect(sizeStates.find((v) => v.value === 'M')?.disabled).toBe(true)
  })
})
