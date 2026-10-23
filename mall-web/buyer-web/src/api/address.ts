import http from './http'

/** 收货地址视图（与后端 AddressView 对齐；雪花 ID 序列化为字符串）。 */
export interface Address {
  id: string
  receiver: string
  phone: string
  province: string
  city: string
  district: string
  detail: string
  isDefault: number
}

/** 新增/编辑请求；isDefault=true 时服务端同事务清旧默认。 */
export interface AddressSavePayload {
  receiver: string
  phone: string
  province: string
  city: string
  district: string
  detail: string
  isDefault?: boolean
}

/** 高德地址识别结果（与后端 AddressGeocodeView 对齐）。 */
export interface AddressGeocodeResult {
  recognized: boolean
  lng: number | null
  lat: number | null
  formattedAddress: string | null
  level: string | null
  message: string
}

/** GET /api/user/address/geocode —— 校验地址能否被高德识别（不落库）。 */
export async function checkAddress(address: string): Promise<AddressGeocodeResult> {
  const res = await http.get('/user/address/geocode', { params: { address } })
  return res.data.data as AddressGeocodeResult
}

/** GET /api/user/address —— 我的地址列表。 */
export async function listAddresses(): Promise<Address[]> {
  const res = await http.get('/user/address')
  return res.data.data as Address[]
}

/** POST /api/user/address —— 新增地址。 */
export async function createAddress(payload: AddressSavePayload): Promise<void> {
  await http.post('/user/address', payload)
}

/** PUT /api/user/address/{id} —— 编辑地址。 */
export async function updateAddress(id: string, payload: AddressSavePayload): Promise<void> {
  await http.put(`/user/address/${id}`, payload)
}

/** DELETE /api/user/address/{id} —— 删除地址（删除默认地址后不落新默认）。 */
export async function deleteAddress(id: string): Promise<void> {
  await http.delete(`/user/address/${id}`)
}

/** POST /api/user/address/{id}/default —— 设为默认。 */
export async function setDefaultAddress(id: string): Promise<void> {
  await http.post(`/user/address/${id}/default`)
}
