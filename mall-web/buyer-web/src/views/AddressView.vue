<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import BuyerHeader from '../components/BuyerHeader.vue'
import {
  checkAddress,
  createAddress,
  deleteAddress,
  listAddresses,
  setDefaultAddress,
  updateAddress,
} from '../api/address'
import type { Address, AddressGeocodeResult } from '../api/address'

const addresses = ref<Address[]>([])
const loading = ref(true)
const errorMsg = ref('')

const showForm = ref(false)
const editingId = ref<string | null>(null)
const saving = ref(false)
const formError = ref('')
const checking = ref(false)
const geocodeResult = ref<AddressGeocodeResult | null>(null)

const form = reactive({
  receiver: '',
  phone: '',
  province: '',
  city: '',
  district: '',
  detail: '',
  isDefault: false,
})

async function load(): Promise<void> {
  loading.value = true
  errorMsg.value = ''
  try {
    addresses.value = await listAddresses()
  } catch (e) {
    errorMsg.value = e instanceof Error ? e.message : '加载失败'
  } finally {
    loading.value = false
  }
}

function openCreate(): void {
  editingId.value = null
  Object.assign(form, {
    receiver: '',
    phone: '',
    province: '',
    city: '',
    district: '',
    detail: '',
    isDefault: addresses.value.length === 0,
  })
  formError.value = ''
  geocodeResult.value = null
  showForm.value = true
}

function openEdit(a: Address): void {
  editingId.value = a.id
  Object.assign(form, {
    receiver: a.receiver,
    phone: a.phone,
    province: a.province,
    city: a.city,
    district: a.district,
    detail: a.detail,
    isDefault: a.isDefault === 1,
  })
  formError.value = ''
  geocodeResult.value = null
  showForm.value = true
}

/** 调后端高德地理编码接口，确认地址能否被识别（只校验，不落库）。 */
async function onCheckAddress(): Promise<void> {
  const full = `${form.province}${form.city}${form.district}${form.detail}`.trim()
  if (!form.province.trim() || !form.city.trim() || !form.district.trim() || !form.detail.trim()) {
    geocodeResult.value = {
      recognized: false,
      lng: null,
      lat: null,
      formattedAddress: null,
      level: null,
      message: '请先填写完整的省、市、区和详细地址',
    }
    return
  }
  checking.value = true
  try {
    geocodeResult.value = await checkAddress(full)
  } catch (e) {
    geocodeResult.value = {
      recognized: false,
      lng: null,
      lat: null,
      formattedAddress: null,
      level: null,
      message: e instanceof Error ? e.message : '地址校验失败',
    }
  } finally {
    checking.value = false
  }
}

async function onSave(): Promise<void> {
  formError.value = ''
  if (!form.receiver.trim() || !form.phone.trim() || !form.province.trim() || !form.city.trim()
    || !form.district.trim() || !form.detail.trim()) {
    formError.value = '请完整填写收货人、手机号与省市区详细地址'
    return
  }
  saving.value = true
  try {
    const payload = {
      receiver: form.receiver.trim(),
      phone: form.phone.trim(),
      province: form.province.trim(),
      city: form.city.trim(),
      district: form.district.trim(),
      detail: form.detail.trim(),
      isDefault: form.isDefault,
    }
    if (editingId.value) {
      await updateAddress(editingId.value, payload)
    } else {
      await createAddress(payload)
    }
    showForm.value = false
    await load()
  } catch (e) {
    formError.value = e instanceof Error ? e.message : '保存失败'
  } finally {
    saving.value = false
  }
}

async function onDelete(a: Address): Promise<void> {
  if (!confirm(`确定删除地址「${a.receiver} ${a.detail}」吗？`)) return
  try {
    await deleteAddress(a.id)
    await load()
  } catch (e) {
    alert(e instanceof Error ? e.message : '删除失败')
  }
}

async function onSetDefault(a: Address): Promise<void> {
  try {
    await setDefaultAddress(a.id)
    await load()
  } catch (e) {
    alert(e instanceof Error ? e.message : '设置失败')
  }
}

function fullAddress(a: Address): string {
  return `${a.province}${a.city}${a.district}${a.detail}`
}

onMounted(load)
</script>

<template>
  <div class="addr-page">
    <BuyerHeader />
    <main class="addr-page__body">
      <div class="addr-page__head">
        <h2>收货地址</h2>
        <button class="btn-primary" @click="openCreate">新增地址</button>
      </div>

      <div v-if="loading" class="placeholder">加载中…</div>
      <div v-else-if="errorMsg" class="placeholder">{{ errorMsg }}</div>
      <div v-else-if="addresses.length === 0" class="placeholder">还没有收货地址，点击右上角新增</div>

      <div v-else class="addr-grid">
        <div v-for="a in addresses" :key="a.id" class="addr-card">
          <div class="addr-card__line1">
            <b>{{ a.receiver }}</b>
            <span class="addr-card__phone">{{ a.phone }}</span>
            <span v-if="a.isDefault === 1" class="addr-card__default">默认</span>
          </div>
          <div class="addr-card__line2">{{ fullAddress(a) }}</div>
          <div class="addr-card__actions">
            <button class="link" @click="openEdit(a)">编辑</button>
            <button class="link link--danger" @click="onDelete(a)">删除</button>
            <button v-if="a.isDefault !== 1" class="link" @click="onSetDefault(a)">设为默认</button>
          </div>
        </div>
      </div>

      <div v-if="showForm" class="modal-mask" @click.self="showForm = false">
        <div class="modal">
          <h3 class="modal__title">{{ editingId ? '编辑地址' : '新增地址' }}</h3>
          <div v-if="formError" class="modal__error">{{ formError }}</div>
          <div class="modal__fields">
            <label class="field">
              <span class="field__label">收货人</span>
              <input v-model="form.receiver" class="input" maxlength="32" placeholder="收货人姓名" />
            </label>
            <label class="field">
              <span class="field__label">手机号</span>
              <input v-model="form.phone" class="input" maxlength="20" placeholder="11 位手机号" />
            </label>
            <div class="field-row">
              <label class="field">
                <span class="field__label">省</span>
                <input v-model="form.province" class="input" placeholder="如：浙江省" />
              </label>
              <label class="field">
                <span class="field__label">市</span>
                <input v-model="form.city" class="input" placeholder="如：杭州市" />
              </label>
              <label class="field">
                <span class="field__label">区/县</span>
                <input v-model="form.district" class="input" placeholder="如：西湖区" />
              </label>
            </div>
            <label class="field">
              <span class="field__label">详细地址</span>
              <textarea
                v-model="form.detail"
                class="input input--area"
                maxlength="255"
                rows="3"
                placeholder="街道、门牌号、小区等"
              ></textarea>
            </label>
            <div class="geo-check">
              <button class="btn-ghost geo-check__btn" :disabled="checking" @click="onCheckAddress">
                {{ checking ? '校验中…' : '校验高德识别' }}
              </button>
              <div
                v-if="geocodeResult"
                class="geo-check__result"
                :class="geocodeResult.recognized ? 'geo-check__result--ok' : 'geo-check__result--fail'"
              >
                <template v-if="geocodeResult.recognized">
                  已识别：{{ geocodeResult.formattedAddress || '—' }}
                  <span class="geo-check__coord">
                    ({{ geocodeResult.lng }}, {{ geocodeResult.lat }}，{{ geocodeResult.level }})
                  </span>
                </template>
                <template v-else>{{ geocodeResult.message }}</template>
              </div>
            </div>
            <label class="field field--check">
              <input v-model="form.isDefault" type="checkbox" />
              <span>设为默认地址</span>
            </label>
          </div>
          <div class="modal__actions">
            <button class="btn-ghost" :disabled="saving" @click="showForm = false">取消</button>
            <button class="btn-primary" :disabled="saving" @click="onSave">
              {{ saving ? '保存中…' : '保存' }}
            </button>
          </div>
        </div>
      </div>
    </main>
  </div>
</template>

<style scoped>
.addr-page__body {
  max-width: 1000px;
  margin: 0 auto;
  padding: 20px 16px 40px;
}

.addr-page__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 18px;
}

.addr-page__head h2 {
  margin: 0;
}

.placeholder {
  padding: 80px 0;
  text-align: center;
  color: var(--wood-accent);
}

.addr-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(300px, 1fr));
  gap: 14px;
}

.addr-card {
  background: var(--wood-surface);
  border: 1px solid var(--wood-border);
  border-radius: 10px;
  padding: 16px 18px;
}

.addr-card__line1 {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 6px;
}

.addr-card__phone {
  color: var(--wood-accent);
  font-size: 13px;
}

.addr-card__default {
  font-size: 11px;
  color: var(--wood-primary-dark);
  border: 1px solid var(--wood-primary);
  border-radius: 999px;
  padding: 1px 8px;
}

.addr-card__line2 {
  color: var(--wood-accent);
  font-size: 13px;
  min-height: 34px;
}

.addr-card__actions {
  display: flex;
  gap: 14px;
  margin-top: 10px;
  padding-top: 10px;
  border-top: 1px dashed var(--wood-border);
}

.link {
  border: none;
  background: none;
  padding: 0;
  color: var(--wood-primary);
  font-size: 13px;
  cursor: pointer;
}

.link--danger {
  color: #b33;
}

.btn-primary {
  height: 36px;
  padding: 0 18px;
  border: none;
  border-radius: 8px;
  background: var(--wood-primary);
  color: #fff;
  font-size: 14px;
  cursor: pointer;
}

.btn-primary:hover:not(:disabled) {
  background: var(--wood-primary-dark);
}

.btn-primary:disabled {
  opacity: 0.6;
  cursor: not-allowed;
}

.btn-ghost {
  height: 36px;
  padding: 0 18px;
  border-radius: 8px;
  font-size: 14px;
  cursor: pointer;
  border: 1px solid var(--wood-border);
  background: transparent;
  color: var(--wood-primary-dark);
}

.modal-mask {
  position: fixed;
  inset: 0;
  background: rgba(74, 55, 40, 0.35);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 100;
  padding: 16px;
}

.modal {
  width: 100%;
  max-width: 480px;
  background: var(--wood-surface);
  border-radius: 12px;
  padding: 24px;
  box-shadow: 0 18px 40px rgba(74, 55, 40, 0.25);
  max-height: 90vh;
  overflow-y: auto;
}

.modal__title {
  margin: 0 0 16px;
}

.modal__error {
  margin-bottom: 12px;
  padding: 8px 12px;
  border-radius: 8px;
  background: #fdf1e7;
  border: 1px solid var(--wood-accent);
  color: #b33;
  font-size: 13px;
}

.field {
  display: block;
  margin-bottom: 12px;
  flex: 1;
}

.field__label {
  display: block;
  margin-bottom: 5px;
  font-size: 13px;
  color: var(--wood-primary-dark);
}

.field-row {
  display: flex;
  gap: 10px;
}

.field--check {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 14px;
}

.geo-check {
  margin: -4px 0 14px;
}

.geo-check__btn {
  height: 32px;
  font-size: 13px;
}

.geo-check__result {
  margin-top: 8px;
  padding: 8px 10px;
  border-radius: 8px;
  font-size: 12px;
  line-height: 1.6;
  word-break: break-all;
}

.geo-check__result--ok {
  background: #fdf1e7;
  border: 1px solid var(--wood-accent);
  color: var(--wood-primary-dark);
}

.geo-check__result--fail {
  background: #fdf0f0;
  border: 1px solid #e0b4b4;
  color: #a33;
}

.geo-check__coord {
  color: var(--wood-accent);
}

.input {
  width: 100%;
  height: 38px;
  padding: 0 12px;
  border: 1px solid var(--wood-border);
  border-radius: 8px;
  background: #fff;
  color: var(--wood-text);
  font-size: 14px;
  outline: none;
  font-family: inherit;
}

.input--area {
  height: auto;
  padding: 10px 12px;
  resize: vertical;
}

.input:focus {
  border-color: var(--wood-primary);
}

.modal__actions {
  display: flex;
  justify-content: flex-end;
  gap: 12px;
  margin-top: 8px;
}
</style>
