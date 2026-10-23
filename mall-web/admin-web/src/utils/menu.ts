export interface MenuNode {
  id: number
  parentId: number | null
  name: string
  code: string
  type: string
  path: string | null
  sort: number
  children?: MenuNode[]
}

/**
 * 把后端返回的扁平菜单列表按 parentId 组装成树，并按 sort 升序排列。
 */
export function buildMenuTree(list: MenuNode[]): MenuNode[] {
  const nodes: MenuNode[] = list.map((item) => ({ ...item, children: [] as MenuNode[] }))
  const index = new Map<number, MenuNode>()
  nodes.forEach((node) => index.set(node.id, node))

  const roots: MenuNode[] = []
  nodes.forEach((node) => {
    const parent = node.parentId === null || node.parentId === undefined ? undefined : index.get(node.parentId)
    if (parent) {
      parent.children!.push(node)
    } else {
      roots.push(node)
    }
  })

  const sortTree = (items: MenuNode[]): void => {
    items.sort((a, b) => a.sort - b.sort)
    items.forEach((item) => sortTree(item.children ?? []))
  }
  sortTree(roots)

  return roots
}

/**
 * 收集菜单树中所有可访问路径（用于路由守卫判定越权直链）。
 */
export function collectMenuPaths(list: MenuNode[]): string[] {
  return buildMenuTree(list)
    .flatMap((node) => flattenPaths(node))
    .filter((path): path is string => Boolean(path))
}

function flattenPaths(node: MenuNode): (string | null)[] {
  const children = node.children ?? []
  return [node.path, ...children.flatMap((child) => flattenPaths(child))]
}
