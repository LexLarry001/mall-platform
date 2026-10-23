<script setup lang="ts">
import type { MenuNode } from '../utils/menu'

defineProps<{ node: MenuNode }>()
</script>

<template>
  <li>
    <template v-if="node.children && node.children.length > 0">
      <div class="menu__group">{{ node.name }}</div>
      <ul>
        <SidebarItem v-for="child in node.children" :key="child.id" :node="child" />
      </ul>
    </template>
    <RouterLink v-else :to="node.path || '/403'" class="menu__link">{{ node.name }}</RouterLink>
  </li>
</template>
