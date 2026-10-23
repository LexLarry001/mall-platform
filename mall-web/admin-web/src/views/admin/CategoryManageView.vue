<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import http from '../../api/http'

interface CategoryNode {
  id: number
  parentId: number
  name: string
  sort: number
  status: number
  children?: CategoryNode[]
}

const loading = ref(false)
const tree = ref<CategoryNode[]>([])
const dialogVisible = ref(false)
const editingId = ref<number | null>(null)
const form = reactive({ parentId: 0, name: '', sort: 0 })

async function load(): Promise<void> {
  loading.value = true
  try {
    const res = await http.get('/admin/category/tree')
    tree.value = (res.data.data || []) as CategoryNode[]
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '加载失败')
  } finally {
    loading.value = false
  }
}

function flatten(nodes: CategoryNode[], depth = 0): { id: number; name: string; depth: number }[] {
  return nodes.flatMap((n) => [
    { id: n.id, name: n.name, depth },
    ...(n.children ? flatten(n.children, depth + 1) : []),
  ])
}

function openCreate(parentId: number): void {
  editingId.value = null
  form.parentId = parentId
  form.name = ''
  form.sort = 0
  dialogVisible.value = true
}

function openEdit(row: CategoryNode): void {
  editingId.value = row.id
  form.parentId = row.parentId
  form.name = row.name
  form.sort = row.sort
  dialogVisible.value = true
}

async function onSave(): Promise<void> {
  if (!form.name.trim()) {
    ElMessage.warning('请输入类目名称')
    return
  }
  try {
    if (editingId.value) {
      await http.put(`/admin/category/${editingId.value}`, { parentId: form.parentId, name: form.name.trim(), sort: form.sort })
      ElMessage.success('已保存')
    } else {
      await http.post('/admin/category', { parentId: form.parentId, name: form.name.trim(), sort: form.sort })
      ElMessage.success('已新增')
    }
    dialogVisible.value = false
    load()
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '保存失败')
  }
}

async function onToggleStatus(row: CategoryNode): Promise<void> {
  try {
    await http.post(`/admin/category/${row.id}/status`, null, { params: { status: row.status === 1 ? 0 : 1 } })
    ElMessage.success(row.status === 1 ? '已停用' : '已启用')
    load()
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '操作失败')
  }
}

async function onDelete(row: CategoryNode): Promise<void> {
  try {
    await ElMessageBox.confirm(`确定删除类目「${row.name}」吗？有子类目或商品引用时将被拒绝`, '删除确认', {
      type: 'warning',
    })
  } catch {
    return
  }
  try {
    await http.delete(`/admin/category/${row.id}`)
    ElMessage.success('已删除')
    load()
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '删除失败')
  }
}

onMounted(load)
</script>

<template>
  <div>
    <el-card>
      <template #header>
        <div class="card-header">
          <span>类目管理</span>
          <el-button type="primary" @click="openCreate(0)">新增顶级类目</el-button>
        </div>
      </template>

      <el-table v-loading="loading" :data="tree" row-key="id" default-expand-all style="width: 100%">
        <el-table-column prop="name" label="类目名称" min-width="220" />
        <el-table-column prop="id" label="ID" width="110" />
        <el-table-column prop="sort" label="排序" width="80" />
        <el-table-column label="状态" width="100">
          <template #default="{ row }">
            <el-tag :type="row.status === 1 ? 'success' : 'info'">{{ row.status === 1 ? '启用' : '停用' }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="280">
          <template #default="{ row }">
            <el-button size="small" @click="openCreate(row.id)">新增子类</el-button>
            <el-button size="small" type="primary" plain @click="openEdit(row)">编辑</el-button>
            <el-button size="small" :type="row.status === 1 ? 'warning' : 'success'" plain @click="onToggleStatus(row)">
              {{ row.status === 1 ? '停用' : '启用' }}
            </el-button>
            <el-button size="small" type="danger" plain @click="onDelete(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <el-dialog v-model="dialogVisible" :title="editingId ? '编辑类目' : '新增类目'" width="420px">
      <el-form label-width="90px">
        <el-form-item label="父类目">
          <el-select v-model="form.parentId" :disabled="!!editingId" style="width: 100%">
            <el-option :value="0" label="顶级类目" />
            <el-option
              v-for="opt in flatten(tree).filter((o) => o.depth < 1)"
              :key="opt.id"
              :value="opt.id"
              :label="opt.name"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="名称" required>
          <el-input v-model="form.name" maxlength="32" />
        </el-form-item>
        <el-form-item label="排序">
          <el-input-number v-model="form.sort" :min="0" style="width: 160px" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" @click="onSave">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.card-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
}
</style>
