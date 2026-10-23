<script setup lang="ts">
import { nextTick, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  changeRoleStatus,
  createRole,
  deleteRole,
  listPermissions,
  listRoles,
  updateRole,
} from '../../api/system'
import type { AdminRoleView, Id, PermissionView } from '../../api/system'

interface PermissionTreeNode {
  id: Id
  label: string
  type: string
  status: number
  children: PermissionTreeNode[]
}

interface TreeExpose {
  setCheckedKeys(keys: Id[]): void
  getCheckedKeys(leafOnly?: boolean): Id[]
  getHalfCheckedKeys(): Id[]
}

const loading = ref(false)
const roles = ref<AdminRoleView[]>([])
const permissions = ref<PermissionView[]>([])
const treeData = ref<PermissionTreeNode[]>([])

const dialogVisible = ref(false)
const saving = ref(false)
const editing = ref<AdminRoleView | null>(null)
const treeRef = ref<TreeExpose | null>(null)
const form = reactive({
  name: '',
  code: '',
  status: 1,
})

const dialogTitle = () => (editing.value ? '编辑角色' : '新增角色')
const isSuperRole = (role: AdminRoleView): boolean => role.code === 'ROLE_SUPER'
const sameId = (a: Id, b: Id): boolean => String(a) === String(b)

function buildTree(list: PermissionView[]): PermissionTreeNode[] {
  const nodes = new Map<string, PermissionTreeNode>()
  list.forEach((item) => {
    nodes.set(String(item.id), {
      id: item.id,
      label: item.type === 'BUTTON' ? `${item.name}（按钮）` : item.name,
      type: item.type,
      status: item.status,
      children: [],
    })
  })
  const roots: PermissionTreeNode[] = []
  list.forEach((item) => {
    const node = nodes.get(String(item.id))!
    const parent = item.parentId == null ? undefined : nodes.get(String(item.parentId))
    if (parent) {
      parent.children.push(node)
    } else {
      roots.push(node)
    }
  })
  const sortTree = (items: PermissionTreeNode[]): void => {
    items.sort((a, b) => {
      const left = permissions.value.find((p) => sameId(p.id, a.id))?.sort ?? 0
      const right = permissions.value.find((p) => sameId(p.id, b.id))?.sort ?? 0
      return left - right
    })
    items.forEach((item) => sortTree(item.children))
  }
  sortTree(roots)
  return roots
}

function leafIds(nodes: PermissionTreeNode[], selected: Set<string>, out: Id[]): void {
  nodes.forEach((node) => {
    if (node.children.length === 0) {
      if (selected.has(String(node.id))) out.push(node.id)
      return
    }
    leafIds(node.children, selected, out)
  })
}

async function load(): Promise<void> {
  loading.value = true
  try {
    const [roleList, permissionList] = await Promise.all([listRoles(), listPermissions()])
    roles.value = roleList
    permissions.value = permissionList
    treeData.value = buildTree(permissionList)
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '加载失败')
  } finally {
    loading.value = false
  }
}

function openCreate(): void {
  editing.value = null
  form.name = ''
  form.code = 'ROLE_'
  form.status = 1
  dialogVisible.value = true
  nextTick(() => treeRef.value?.setCheckedKeys([]))
}

function openEdit(row: AdminRoleView): void {
  editing.value = row
  form.name = row.name
  form.code = row.code
  form.status = row.status
  dialogVisible.value = true
  nextTick(() => {
    const selected = new Set(row.permissionIds.map((id) => String(id)))
    const leaves: Id[] = []
    leafIds(treeData.value, selected, leaves)
    treeRef.value?.setCheckedKeys(leaves)
  })
}

async function onSave(): Promise<void> {
  if (!form.name.trim()) {
    ElMessage.warning('请输入角色名称')
    return
  }
  if (!form.code.trim().startsWith('ROLE_')) {
    ElMessage.warning('角色标识必须以 ROLE_ 开头')
    return
  }
  const checked = treeRef.value?.getCheckedKeys(false) ?? []
  const half = treeRef.value?.getHalfCheckedKeys() ?? []
  const permissionIds = Array.from(new Set([...checked, ...half].map((id) => String(id)))).map((id) => {
    const found = permissions.value.find((p) => String(p.id) === id)
    return found ? found.id : id
  })

  saving.value = true
  try {
    if (editing.value) {
      await updateRole(editing.value.id, {
        name: form.name.trim(),
        code: form.code.trim(),
        status: form.status,
        permissionIds,
      })
      ElMessage.success('已保存')
    } else {
      await createRole({
        name: form.name.trim(),
        code: form.code.trim(),
        status: form.status,
        permissionIds,
      })
      ElMessage.success('已新增')
    }
    dialogVisible.value = false
    await load()
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '保存失败')
  } finally {
    saving.value = false
  }
}

async function onToggleStatus(row: AdminRoleView): Promise<void> {
  const target = row.status === 1 ? 0 : 1
  const action = target === 0 ? '停用' : '启用'
  try {
    await ElMessageBox.confirm(`确定${action}角色「${row.name}」吗？`, `${action}确认`, { type: 'warning' })
  } catch {
    return
  }
  try {
    await changeRoleStatus(row.id, target)
    ElMessage.success(`已${action}`)
    await load()
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '操作失败')
  }
}

async function onDelete(row: AdminRoleView): Promise<void> {
  try {
    await ElMessageBox.confirm(`确定删除角色「${row.name}」吗？`, '删除确认', { type: 'warning' })
  } catch {
    return
  }
  try {
    await deleteRole(row.id)
    ElMessage.success('已删除')
    await load()
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '删除失败')
  }
}

function formatTime(value: string | number[] | null): string {
  if (!value) return '-'
  if (typeof value === 'string') return value.replace('T', ' ').slice(0, 19)
  const [y, m, d, h, mi, s] = value
  const pad = (n: number): string => String(n).padStart(2, '0')
  return `${y}-${pad(m)}-${pad(d)} ${pad(h)}:${pad(mi)}:${pad(s)}`
}

onMounted(load)
</script>

<template>
  <div>
    <el-card>
      <template #header>
        <div class="card-header">
          <span>角色管理</span>
          <el-button type="primary" @click="openCreate">新增角色</el-button>
        </div>
      </template>

      <el-table v-loading="loading" :data="roles" style="width: 100%">
        <el-table-column prop="name" label="角色名称" min-width="150" />
        <el-table-column prop="code" label="角色标识" min-width="180" />
        <el-table-column label="权限数" width="100">
          <template #default="{ row }">{{ row.permissionIds.length }}</template>
        </el-table-column>
        <el-table-column label="状态" width="100">
          <template #default="{ row }">
            <el-tag :type="row.status === 1 ? 'success' : 'danger'">
              {{ row.status === 1 ? '启用' : '停用' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="创建时间" width="180">
          <template #default="{ row }">{{ formatTime(row.createTime) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="280" fixed="right">
          <template #default="{ row }">
            <el-button size="small" :disabled="isSuperRole(row)" @click="openEdit(row)">编辑</el-button>
            <el-button
              size="small"
              :type="row.status === 1 ? 'warning' : 'primary'"
              :disabled="isSuperRole(row)"
              @click="onToggleStatus(row)"
            >
              {{ row.status === 1 ? '停用' : '启用' }}
            </el-button>
            <el-button size="small" type="danger" :disabled="isSuperRole(row)" @click="onDelete(row)">
              删除
            </el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <el-dialog v-model="dialogVisible" :title="dialogTitle()" width="620px">
      <el-form label-width="90px">
        <el-form-item label="角色名称" required>
          <el-input v-model="form.name" placeholder="不超过 32 个字符" />
        </el-form-item>
        <el-form-item label="角色标识" required>
          <el-input v-model="form.code" placeholder="ROLE_XXX" />
        </el-form-item>
        <el-form-item label="状态">
          <el-switch v-model="form.status" :active-value="1" :inactive-value="0" active-text="启用" inactive-text="停用" />
        </el-form-item>
        <el-form-item label="权限">
          <div class="perm-box">
            <el-tree
              ref="treeRef"
              :data="treeData"
              node-key="id"
              show-checkbox
              default-expand-all
              :props="{ label: 'label', children: 'children' }"
            />
          </div>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="onSave">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.card-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
}
.perm-box {
  width: 100%;
  max-height: 320px;
  overflow: auto;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 6px;
  padding: 8px;
}
</style>
