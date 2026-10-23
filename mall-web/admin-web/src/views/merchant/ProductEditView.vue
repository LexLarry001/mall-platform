<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, reactive, ref, shallowRef, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import '@wangeditor/editor/dist/css/style.css'
import { Editor, Toolbar } from '@wangeditor/editor-for-vue'
import type { IDomEditor, IEditorConfig } from '@wangeditor/editor'
import http from '../../api/http'
import { uploadImage } from '../../utils/upload'

interface CategoryNode {
  id: number
  name: string
  children?: CategoryNode[]
}

interface SpecRow {
  specName: string
  valuesText: string
}

interface SkuRow {
  key: string
  specValues: Record<string, string>
  price: number | null
  stock: number | null
  skuImg: string
}

const route = useRoute()
const router = useRouter()
const spuId = typeof route.query.spuId === 'string' ? route.query.spuId : null

const saving = ref(false)
const categories = ref<CategoryNode[]>([])
const categoryPath = ref<number[]>([])
const form = reactive({
  name: '',
  subTitle: '',
  mainImg: '',
  imgList: [] as string[],
})
const specs = ref<SpecRow[]>([{ specName: '', valuesText: '' }])
const skuRows = ref<SkuRow[]>([])

// ---------- 富文本 ----------
const editorRef = shallowRef<IDomEditor>()
const detailHtml = ref('')
const editorConfig: Partial<IEditorConfig> = {
  placeholder: '请输入商品图文详情…',
  MENU_CONF: {
    uploadImage: {
      async customUpload(file: File, insertFn: (url: string, alt: string, href: string) => void) {
        try {
          const url = await uploadImage(file)
          insertFn(url, '', url)
        } catch (e) {
          ElMessage.error(e instanceof Error ? e.message : '图片上传失败')
        }
      },
    },
  },
}
function onEditorCreated(editor: IDomEditor): void {
  editorRef.value = editor
}
onBeforeUnmount(() => editorRef.value?.destroy())

// ---------- 类目 ----------
interface CascaderOption {
  value: number
  label: string
  children?: CascaderOption[]
}
const categoryOptions = computed<CascaderOption[]>(() => buildOptions(categories.value))
function buildOptions(nodes: CategoryNode[]): CascaderOption[] {
  return nodes.map((n) => ({
    value: n.id,
    label: n.name,
    children: n.children && n.children.length ? buildOptions(n.children) : undefined,
  }))
}
async function loadCategories(): Promise<void> {
  const res = await http.get('/category/tree')
  categories.value = (res.data.data || []) as CategoryNode[]
}
function findPath(nodes: CategoryNode[], id: number, trail: number[] = []): number[] | null {
  for (const n of nodes) {
    const next = [...trail, n.id]
    if (n.id === id) return next
    const hit = n.children ? findPath(n.children, id, next) : null
    if (hit) return hit
  }
  return null
}

// ---------- 图片上传 ----------
async function onUploadMain(e: Event): Promise<void> {
  const input = e.target as HTMLInputElement
  const file = input.files?.[0]
  if (!file) return
  try {
    form.mainImg = await uploadImage(file)
  } catch (err) {
    ElMessage.error(err instanceof Error ? err.message : '上传失败')
  } finally {
    input.value = ''
  }
}
async function onUploadGallery(e: Event): Promise<void> {
  const input = e.target as HTMLInputElement
  const files = Array.from(input.files || [])
  for (const file of files) {
    try {
      form.imgList.push(await uploadImage(file))
    } catch (err) {
      ElMessage.error(err instanceof Error ? err.message : '上传失败')
      break
    }
  }
  input.value = ''
}
function removeGallery(index: number): void {
  form.imgList.splice(index, 1)
}
async function onUploadSkuImg(row: SkuRow, e: Event): Promise<void> {
  const input = e.target as HTMLInputElement
  const file = input.files?.[0]
  if (!file) return
  try {
    row.skuImg = await uploadImage(file)
  } catch (err) {
    ElMessage.error(err instanceof Error ? err.message : '上传失败')
  } finally {
    input.value = ''
  }
}

// ---------- 规格 / SKU ----------
function addSpec(): void {
  if (specs.value.length >= 3) {
    ElMessage.warning('最多支持 3 项规格')
    return
  }
  specs.value.push({ specName: '', valuesText: '' })
}
function removeSpec(index: number): void {
  specs.value.splice(index, 1)
}
function parseValues(text: string): string[] {
  return text
    .split(/[,，]/)
    .map((v) => v.trim())
    .filter(Boolean)
}
function comboKey(specValues: Record<string, string>): string {
  return Object.keys(specValues)
    .sort()
    .map((k) => `${k}=${specValues[k]}`)
    .join('|')
}
function rebuildSkus(): void {
  const validSpecs = specs.value
    .map((s) => ({ specName: s.specName.trim(), values: parseValues(s.valuesText) }))
    .filter((s) => s.specName && s.values.length > 0)
  if (validSpecs.length === 0) {
    skuRows.value = []
    return
  }
  let combos: Record<string, string>[] = [{}]
  for (const spec of validSpecs) {
    const next: Record<string, string>[] = []
    for (const combo of combos) {
      for (const value of spec.values) {
        next.push({ ...combo, [spec.specName]: value })
      }
    }
    combos = next
  }
  const old = new Map(skuRows.value.map((r) => [r.key, r]))
  skuRows.value = combos.map((specValues) => {
    const key = comboKey(specValues)
    const prev = old.get(key)
    return {
      key,
      specValues,
      price: prev?.price ?? null,
      stock: prev?.stock ?? null,
      skuImg: prev?.skuImg ?? '',
    }
  })
}
watch(specs, rebuildSkus, { deep: true })

// ---------- 编辑回显 ----------
async function loadDetail(): Promise<void> {
  if (!spuId) return
  const res = await http.get(`/merchant/product/${spuId}`)
  const data = res.data.data as {
    categoryId: number
    name: string
    subTitle: string | null
    mainImg: string
    imgList: string[] | null
    specs: { specName: string; values: string[] }[]
    skus: { specValues: Record<string, string>; price: number; stock: number; skuImg: string | null }[]
    detailHtml: string | null
  }
  form.name = data.name
  form.subTitle = data.subTitle ?? ''
  form.mainImg = data.mainImg
  form.imgList = data.imgList ?? []
  detailHtml.value = data.detailHtml ?? ''
  specs.value = data.specs?.length
    ? data.specs.map((s) => ({ specName: s.specName, valuesText: s.values.join(',') }))
    : [{ specName: '', valuesText: '' }]
  rebuildSkus()
  const old = new Map((data.skus || []).map((s) => [comboKey(s.specValues), s]))
  skuRows.value.forEach((row) => {
    const hit = old.get(row.key)
    if (hit) {
      row.price = hit.price
      row.stock = hit.stock
      row.skuImg = hit.skuImg ?? ''
    }
  })
  await loadCategories()
  const path = findPath(categories.value, data.categoryId)
  if (path) categoryPath.value = path
}

// ---------- AI 文案生成 ----------
type CopyStyle = 'ECOMMERCE' | 'XIAOHONGSHU' | 'MINIMALIST' | 'PROFESSIONAL'
interface CopyResult {
  title: string
  subtitle: string
  description: string
  sellingPoints: string[]
  source: string
  model: string
}

const copyDialogVisible = ref(false)
const copyLoading = ref(false)
const copyStyle = ref<CopyStyle>('ECOMMERCE')
const copyResult = ref<CopyResult | null>(null)

const copyStyleOptions: { value: CopyStyle; label: string }[] = [
  { value: 'ECOMMERCE', label: '电商详情风' },
  { value: 'XIAOHONGSHU', label: '小红书种草风' },
  { value: 'MINIMALIST', label: '极简风' },
  { value: 'PROFESSIONAL', label: '专业参数风' },
]

function findCategoryName(nodes: CategoryNode[], id?: number): string {
  if (!id) return ''
  for (const n of nodes) {
    if (n.id === id) return n.name
    const hit = n.children ? findCategoryName(n.children, id) : ''
    if (hit) return hit
  }
  return ''
}

function openCopyDialog(): void {
  if (!form.name.trim()) {
    ElMessage.warning('请先填写商品名称，再生成文案')
    return
  }
  copyResult.value = null
  copyDialogVisible.value = true
}

async function generateCopy(): Promise<void> {
  if (copyLoading.value) return
  const categoryId = categoryPath.value[categoryPath.value.length - 1]
  const prices = skuRows.value.map((r) => r.price).filter((p): p is number => p !== null)
  const payload = {
    name: form.name.trim(),
    categoryName: findCategoryName(categories.value, categoryId) || undefined,
    price: prices.length ? Math.min(...prices) : undefined,
    specs: specs.value
      .map((s) => ({ specName: s.specName.trim(), values: parseValues(s.valuesText) }))
      .filter((s) => s.specName && s.values.length > 0),
    style: copyStyle.value,
    types: ['TITLE', 'SUBTITLE', 'DESCRIPTION', 'SELLING_POINTS'],
  }
  copyLoading.value = true
  try {
    // 真实模型生成耗时可达 30s，单独放宽超时，避免默认 10s 中断
    const res = await http.post('/merchant/product/copy/generate', payload, { timeout: 45000 })
    copyResult.value = res.data.data as CopyResult
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '文案生成失败')
  } finally {
    copyLoading.value = false
  }
}

function escapeHtml(text: string): string {
  return text.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
}

function textToHtml(text: string): string {
  return text
    .split(/\n+/)
    .map((p) => p.trim())
    .filter(Boolean)
    .map((p) => `<p>${escapeHtml(p)}</p>`)
    .join('')
}

function applyCopy(): void {
  const r = copyResult.value
  if (!r) return
  if (r.title) form.name = r.title.slice(0, 64)
  if (r.subtitle) form.subTitle = r.subtitle.slice(0, 128)
  if (r.description) detailHtml.value = textToHtml(r.description)
  ElMessage.success('已应用到表单，可继续手动调整')
  copyDialogVisible.value = false
}

// ---------- 提交 ----------
async function onSubmit(): Promise<void> {
  if (saving.value) return
  const categoryId = categoryPath.value[categoryPath.value.length - 1]
  if (!categoryId) {
    ElMessage.warning('请选择商品类目')
    return
  }
  if (!form.name.trim()) {
    ElMessage.warning('请输入商品名称')
    return
  }
  if (!form.mainImg) {
    ElMessage.warning('请上传商品主图')
    return
  }
  if (skuRows.value.length === 0) {
    ElMessage.warning('请填写规格并生成 SKU')
    return
  }
  for (const row of skuRows.value) {
    if (row.price === null || row.price < 0) {
      ElMessage.warning('请填写所有 SKU 的价格')
      return
    }
    if (row.stock === null || row.stock < 0) {
      ElMessage.warning('请填写所有 SKU 的库存')
      return
    }
  }
  const payload = {
    categoryId,
    name: form.name.trim(),
    subTitle: form.subTitle.trim() || null,
    mainImg: form.mainImg,
    imgList: form.imgList,
    specs: specs.value
      .map((s) => ({ specName: s.specName.trim(), values: parseValues(s.valuesText) }))
      .filter((s) => s.specName && s.values.length > 0),
    skus: skuRows.value.map((r) => ({
      specValues: r.specValues,
      price: r.price,
      stock: r.stock,
      skuImg: r.skuImg || null,
    })),
    detailHtml: detailHtml.value || null,
  }
  saving.value = true
  try {
    if (spuId) {
      await http.put(`/merchant/product/${spuId}`, payload)
      ElMessage.success('商品已保存，重新进入待审核状态')
    } else {
      await http.post('/merchant/product', payload)
      ElMessage.success('商品已发布，等待平台审核')
    }
    await router.push({ name: 'mProductList' })
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '保存失败')
  } finally {
    saving.value = false
  }
}

onMounted(async () => {
  await loadCategories()
  await loadDetail()
})
</script>

<template>
  <div>
    <el-card>
      <template #header>
        <div class="card-header">
          <span>{{ spuId ? '编辑商品' : '发布商品' }}</span>
          <div class="header-actions">
            <el-button type="primary" plain @click="openCopyDialog">AI 生成文案</el-button>
            <el-button @click="router.push({ name: 'mProductList' })">返回列表</el-button>
          </div>
        </div>
      </template>

      <el-form label-width="110px" style="max-width: 860px">
        <el-form-item label="商品类目" required>
          <el-cascader
            v-model="categoryPath"
            :options="categoryOptions"
            :props="{ checkStrictly: true }"
            placeholder="请选择类目"
            style="width: 320px"
          />
        </el-form-item>
        <el-form-item label="商品名称" required>
          <el-input v-model="form.name" maxlength="64" show-word-limit />
        </el-form-item>
        <el-form-item label="副标题">
          <el-input v-model="form.subTitle" maxlength="128" placeholder="卖点/促销语（选填）" />
        </el-form-item>
        <el-form-item label="商品主图" required>
          <div class="img-row">
            <img v-if="form.mainImg" :src="form.mainImg" alt="主图" class="preview" />
            <input type="file" accept="image/jpeg,image/png,image/webp" @change="onUploadMain" />
            <el-input v-model="form.mainImg" placeholder="或粘贴图片 URL" size="small" style="width: 280px" />
          </div>
        </el-form-item>
        <el-form-item label="商品图集">
          <div class="gallery">
            <div v-for="(img, i) in form.imgList" :key="img + i" class="gallery__item">
              <img :src="img" alt="" class="preview" />
              <el-button size="small" circle type="danger" class="gallery__del" @click="removeGallery(i)">×</el-button>
            </div>
            <input type="file" multiple accept="image/jpeg,image/png,image/webp" @change="onUploadGallery" />
          </div>
        </el-form-item>

        <el-form-item label="商品规格">
          <div class="specs">
            <div v-for="(spec, i) in specs" :key="i" class="spec-row">
              <el-input v-model="spec.specName" placeholder="规格名，如 颜色" maxlength="32" style="width: 180px" />
              <el-input
                v-model="spec.valuesText"
                placeholder="规格值，用逗号分隔，如 曜石黑,月牙白"
                style="width: 380px"
              />
              <el-button size="small" type="danger" plain :disabled="specs.length <= 1" @click="removeSpec(i)">
                删除
              </el-button>
            </div>
            <el-button size="small" type="primary" plain @click="addSpec">+ 添加规格</el-button>
          </div>
        </el-form-item>

        <el-form-item label="SKU 列表" required>
          <el-table v-if="skuRows.length" :data="skuRows" size="small" border style="width: 100%">
            <el-table-column v-for="name in Object.keys(skuRows[0].specValues).sort()" :key="name" :label="name" width="110">
              <template #default="{ row }">{{ row.specValues[name] }}</template>
            </el-table-column>
            <el-table-column label="价格(元)" width="150">
              <template #default="{ row }">
                <el-input-number v-model="row.price" :min="0" :precision="2" size="small" style="width: 130px" />
              </template>
            </el-table-column>
            <el-table-column label="库存" width="130">
              <template #default="{ row }">
                <el-input-number v-model="row.stock" :min="0" :precision="0" size="small" style="width: 110px" />
              </template>
            </el-table-column>
            <el-table-column label="规格图" min-width="140">
              <template #default="{ row }">
                <div class="img-row">
                  <img v-if="row.skuImg" :src="row.skuImg" alt="" class="preview preview--sm" />
                  <input type="file" accept="image/jpeg,image/png,image/webp" @change="onUploadSkuImg(row, $event)" />
                </div>
              </template>
            </el-table-column>
          </el-table>
          <p v-else class="hint">填写规格名与规格值后自动生成 SKU 组合</p>
        </el-form-item>

        <el-form-item label="图文详情">
          <div class="editor-wrap">
            <Toolbar class="editor-toolbar" :editor="editorRef" :default-config="{}" mode="default" />
            <Editor
              v-model="detailHtml"
              class="editor-content"
              :default-config="editorConfig"
              mode="default"
              @on-created="onEditorCreated"
            />
          </div>
        </el-form-item>

        <el-form-item>
          <el-button type="primary" :loading="saving" @click="onSubmit">
            {{ spuId ? '保存修改' : '提交审核' }}
          </el-button>
        </el-form-item>
      </el-form>

      <el-dialog v-model="copyDialogVisible" title="AI 生成商品文案" width="720px" destroy-on-close>
        <el-form label-width="88px">
          <el-form-item label="文案风格">
            <el-radio-group v-model="copyStyle">
              <el-radio-button v-for="opt in copyStyleOptions" :key="opt.value" :value="opt.value">
                {{ opt.label }}
              </el-radio-button>
            </el-radio-group>
          </el-form-item>
          <el-form-item label="生成依据">
            <span class="hint">商品名称、类目、规格与最低价；生成后可直接应用到表单</span>
          </el-form-item>
        </el-form>

        <div v-loading="copyLoading" class="copy-result">
          <template v-if="copyResult">
            <el-alert
              v-if="copyResult.source === 'template'"
              type="info"
              :closable="false"
              show-icon
              title="当前为模板生成（AI 未启用或调用失败），文案可直接使用，也可稍后重试"
            />
            <el-descriptions :column="1" border>
              <el-descriptions-item label="标题">{{ copyResult.title }}</el-descriptions-item>
              <el-descriptions-item label="副标题">{{ copyResult.subtitle }}</el-descriptions-item>
              <el-descriptions-item label="详情">
                <div class="copy-text">{{ copyResult.description }}</div>
              </el-descriptions-item>
              <el-descriptions-item label="卖点">
                <el-tag v-for="p in copyResult.sellingPoints" :key="p" class="copy-tag">{{ p }}</el-tag>
              </el-descriptions-item>
            </el-descriptions>
            <p class="hint">来源：{{ copyResult.source === 'ai' ? copyResult.model : '本地模板' }}</p>
          </template>
          <p v-else class="hint copy-empty">选择风格后点击「生成文案」</p>
        </div>

        <template #footer>
          <el-button @click="copyDialogVisible = false">取消</el-button>
          <el-button :loading="copyLoading" @click="generateCopy">生成文案</el-button>
          <el-button type="primary" :disabled="!copyResult" @click="applyCopy">应用到表单</el-button>
        </template>
      </el-dialog>
    </el-card>
  </div>
</template>

<style scoped>
.card-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
}
.img-row {
  display: flex;
  align-items: center;
  gap: 12px;
}
.preview {
  width: 72px;
  height: 72px;
  object-fit: cover;
  border-radius: 8px;
  border: 1px solid #e5d5c0;
}
.preview--sm {
  width: 44px;
  height: 44px;
}
.gallery {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 12px;
}
.gallery__item {
  position: relative;
}
.gallery__del {
  position: absolute;
  top: -8px;
  right: -8px;
  width: 20px;
  height: 20px;
  padding: 0;
}
.specs {
  display: flex;
  flex-direction: column;
  gap: 10px;
  width: 100%;
}
.spec-row {
  display: flex;
  gap: 10px;
  align-items: center;
}
.hint {
  color: #9c8465;
  font-size: 12px;
  margin: 0;
}
.editor-wrap {
  width: 100%;
  border: 1px solid #e5d5c0;
  border-radius: 8px;
  overflow: hidden;
}
.editor-toolbar {
  border-bottom: 1px solid #e5d5c0;
}
.editor-content {
  height: 320px !important;
  overflow-y: hidden;
}
.header-actions {
  display: flex;
  gap: 10px;
}
.copy-result {
  min-height: 180px;
}
.copy-text {
  white-space: pre-wrap;
  line-height: 1.7;
  color: #5b4636;
}
.copy-tag {
  margin: 0 8px 8px 0;
}
.copy-empty {
  text-align: center;
  padding: 56px 0;
}
</style>
