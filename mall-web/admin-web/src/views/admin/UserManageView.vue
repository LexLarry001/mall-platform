<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  changeUserStatus,
  createUser,
  deleteUser,
  listRoles,
  listUsers,
  updateUser,
} from '../../api/system'
import type { AdminRoleView, AdminUserView, Id } from '../../api/system'
import { useAuthStore } from '../../stores/auth'

const auth = useAuthStore()

const loading = ref(false)
const users = ref<AdminUserView[]>([])
const roles = ref<AdminRoleView[]>([])
const keyword = ref('')

const dialogVisible = ref(false)
const saving = ref(false)
const editing = ref<AdminUserView | null>(null)
const form = reactive({
  username: '',
  password: '',
  status: 1,
  roleIds: [] as Id[],
})

const isBuiltIn = (row: AdminUserView): boolean => String(row.id) === '1'
const isSelf = (row: AdminUserView): boolean => String(row.id) === String(auth.userInfo?.accountId)

const filteredUsers = computed(() => {
  const text = keyword.value.trim().toLowerCase()
  if (!text) return users.value
  return users.value.filter((user) =>
    user.username.toLowerCase().includes(text) || user.roleNames.join(',').toLowerCase().includes(text),
  )
})

const dialogTitle = computed(() => (editing.value ? '编辑管理员' : '新增管理员'))

async function load(): Promise<void> {
  loading.value = true
  try {
    const [userList, roleList] = await Promise.all([listUsers(), listRoles()])
    users.value = userList
    roles.value = roleList
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '加载失败')
  } finally {
    loading.value = false
  }
}

function openCreate(): void {
  editing.value = null
  form.username = ''
  form.password = ''
  form.status = 1
  form.roleIds = []
  dialogVisible.value = true
}

function openEdit(row: AdminUserView): void {
  editing.value = row
  form.username = row.username
  form.password = ''
  form.status = row.status
  form.roleIds = [...row.roleIds]
  dialogVisible.value = true
}

async function onSave(): Promise<void> {
  if (!form.username.trim()) {
    ElMessage.warning('请输入用户名')
    return
  }
  if (!editing.value && !form.password) {
    ElMessage.warning('请输入初始密码')
    return
  }
  if (form.password && form.password.length < 6) {
    ElMessage.warning('密码至少 6 位')
    return
  }
  saving.value = true
  try {
    if (editing.value) {
      await updateUser(editing.value.id, {
        username: form.username.trim(),
        password: form.password || undefined,
        status: form.status,
        roleIds: form.roleIds,
      })
      ElMessage.success('已保存')
    } else {
      await createUser({
        username: form.username.trim(),
        password: form.password,
        status: form.status,
        roleIds: form.roleIds,
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

async function onToggleStatus(row: AdminUserView): Promise<void> {
  const target = row.status === 1 ? 0 : 1
  const action = target === 0 ? '停用' : '启用'
  try {
    await ElMessageBox.confirm(`确定${action}管理员「${row.username}」吗？`, `${action}确认`, { type: 'warning' })
  } catch {
    return
  }
  try {
    await changeUserStatus(row.id, target)
    ElMessage.success(`已${action}`)
    await load()
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '操作失败')
  }
}

async function onDelete(row: AdminUserView): Promise<void> {
  try {
    await ElMessageBox.confirm(`确定删除管理员「${row.username}」吗？删除后不可恢复`, '删除确认', { type: 'warning' })
  } catch {
    return
  }
  try {
    await deleteUser(row.id)
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
          <span>用户管理</span>
          <el-button type="primary" @click="openCreate">新增管理员</el-button>
        </div>
      </template>

      <div class="toolbar">
        <el-input v-model="keyword" placeholder="按用户名/角色筛选" style="width: 240px" clearable />
      </div>

      <el-table v-loading="loading" :data="filteredUsers" style="width: 100%">
        <el-table-column prop="username" label="用户名" min-width="140" />
        <el-table-column label="角色" min-width="180">
          <template #default="{ row }">
            <el-tag v-for="name in row.roleNames" :key="name" size="small" style="margin-right: 6px">
              {{ name }}
            </el-tag>
            <span v-if="row.roleNames.length === 0" class="muted">未分配</span>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="100">
          <template #default="{ row }">
            <el-tag :type="row.status === 1 ? 'success' : 'danger'">
              {{ row.status === 1 ? '正常' : '停用' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="创建时间" width="180">
          <template #default="{ row }">{{ formatTime(row.createTime) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="250" fixed="right">
          <template #default="{ row }">
            <el-button size="small" @click="openEdit(row)">编辑</el-button>
            <el-button
              size="small"
              :type="row.status === 1 ? 'warning' : 'primary'"
              :disabled="isBuiltIn(row) || isSelf(row)"
              @click="onToggleStatus(row)"
            >
              {{ row.status === 1 ? '停用' : '启用' }}
            </el-button>
            <el-button
              size="small"
              type="danger"
              :disabled="isBuiltIn(row) || isSelf(row)"
              @click="onDelete(row)"
            >
              删除
            </el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <el-dialog v-model="dialogVisible" :title="dialogTitle" width="520px">
      <el-form label-width="90px">
        <el-form-item label="用户名" required>
          <el-input v-model="form.username" :disabled="editing !== null" placeholder="3-32 个字符" />
        </el-form-item>
        <el-form-item :label="editing ? '重置密码' : '初始密码'" :required="editing === null">
          <el-input
            v-model="form.password"
            type="password"
            show-password
            :placeholder="editing ? '留空表示不修改' : '6-32 个字符'"
          />
        </el-form-item>
        <el-form-item label="状态">
          <el-switch v-model="form.status" :active-value="1" :inactive-value="0" active-text="正常" inactive-text="停用" />
        </el-form-item>
        <el-form-item label="角色">
          <el-select
            v-model="form.roleIds"
            multiple
            filterable
            style="width: 100%"
            placeholder="请选择角色"
            :disabled="editing !== null && isBuiltIn(editing)"
          >
            <el-option v-for="role in roles" :key="role.id" :label="`${role.name}（${role.code}）`" :value="role.id" />
          </el-select>
          <p v-if="editing !== null && isBuiltIn(editing)" class="muted">内置管理员账号的角色不可修改</p>
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
.toolbar {
  margin-bottom: 16px;
}
.muted {
  color: #909399;
  font-size: 12px;
}
</style>
