import { describe, expect, it } from 'vitest'
import { allChecked, formatDateTime, groupByShop, settleableItems, sumChecked } from '../cart-group'
import type { CartItemLike } from '../cart-group'

function item(partial: Partial<CartItemLike>): CartItemLike {
  return {
    skuId: '1',
    spuId: '11',
    shopId: '100',
    shopName: '木器小筑',
    productName: '商品',
    skuImg: '',
    specJson: '{}',
    price: 10,
    stock: 99,
    quantity: 1,
    checked: true,
    invalid: false,
    ...partial,
  }
}

describe('groupByShop', () => {
  it('按店铺分组，保持首次出现顺序，店铺内保持原顺序', () => {
    const items = [
      item({ skuId: '1', shopId: '100', shopName: 'A店' }),
      item({ skuId: '2', shopId: '200', shopName: 'B店' }),
      item({ skuId: '3', shopId: '100', shopName: 'A店' }),
    ]
    const groups = groupByShop(items)
    expect(groups.map((g) => g.shopId)).toEqual(['100', '200'])
    expect(groups[0].items.map((i) => i.skuId)).toEqual(['1', '3'])
    expect(groups[0].shopName).toBe('A店')
    expect(groups[1].items.map((i) => i.skuId)).toEqual(['2'])
  })

  it('空列表返回空数组', () => {
    expect(groupByShop([])).toEqual([])
  })
})

describe('settleableItems', () => {
  it('仅保留勾选且未失效的条目', () => {
    const items = [
      item({ skuId: '1', checked: true, invalid: false }),
      item({ skuId: '2', checked: false, invalid: false }),
      item({ skuId: '3', checked: true, invalid: true }),
    ]
    expect(settleableItems(items).map((i) => i.skuId)).toEqual(['1'])
  })

  it('全失效或全未勾选时返回空', () => {
    expect(settleableItems([item({ checked: false }), item({ invalid: true })])).toEqual([])
  })
})

describe('sumChecked', () => {
  it('合计仅含勾选且未失效条目，按 price*quantity 累加', () => {
    const items = [
      item({ price: 10.5, quantity: 2, checked: true }),
      item({ skuId: '2', price: 20, quantity: 1, checked: false }),
      item({ skuId: '3', price: 5, quantity: 4, checked: true, invalid: true }),
    ]
    expect(sumChecked(items)).toBeCloseTo(21, 2)
  })

  it('无可结算条目返回 0', () => {
    expect(sumChecked([item({ checked: false }), item({ invalid: true })])).toBe(0)
  })

  it('规避浮点误差：0.1*3 合计为 0.3', () => {
    const items = [item({ price: 0.1, quantity: 3 })]
    expect(sumChecked(items)).toBeCloseTo(0.3, 2)
    expect(Number(sumChecked(items).toFixed(2))).toBe(0.3)
  })
})

describe('allChecked', () => {
  it('全部有效条目勾选时为 true', () => {
    const items = [item({ checked: true }), item({ skuId: '2', checked: true, invalid: true })]
    expect(allChecked(items)).toBe(true)
  })

  it('存在未勾选的有效条目时为 false', () => {
    const items = [item({ checked: true }), item({ skuId: '2', checked: false })]
    expect(allChecked(items)).toBe(false)
  })

  it('无有效条目时为 false（空购物车/全失效不允许全选）', () => {
    expect(allChecked([])).toBe(false)
    expect(allChecked([item({ invalid: true, checked: true })])).toBe(false)
  })
})

describe('formatDateTime', () => {
  it('ISO 字符串格式化为 YYYY-MM-DD HH:mm:ss', () => {
    expect(formatDateTime('2026-10-21T10:30:05')).toBe('2026-10-21 10:30:05')
  })

  it('Jackson 数组格式 [y,m,d,h,mi,s] 同样支持', () => {
    expect(formatDateTime([2026, 10, 21, 10, 30, 5])).toBe('2026-10-21 10:30:05')
    expect(formatDateTime([2026, 1, 5, 3, 4, 9])).toBe('2026-01-05 03:04:09')
  })

  it('空值返回占位符', () => {
    expect(formatDateTime(null)).toBe('-')
    expect(formatDateTime(undefined)).toBe('-')
  })
})
