import type { McpTool } from '@/api'

const categoryLabels: Record<string, string> = {
  CONTENT_SEARCH: '内容搜索',
  POST: '文章管理',
  PAGE: '页面管理',
  CATEGORY: '分类',
  TAG: '标签',
  COMMENT: '评论管理',
  ATTACHMENT: '附件管理',
  THEME: '主题',
  PLUGIN: '插件工具',
}

export function toolCategoryLabel(category: string) {
  return categoryLabels[category] ?? category
}

export interface ToolCategoryGroup {
  category: string
  label: string
  tools: McpTool[]
}

export interface ToolSourceGroup {
  source: McpTool['source']
  categories: ToolCategoryGroup[]
  toolCount: number
}

export function groupTools(tools: McpTool[]): ToolSourceGroup[] {
  const sourceGroups = new Map<
    string,
    {
      source: McpTool['source']
      categories: Map<string, McpTool[]>
    }
  >()

  for (const tool of tools) {
    let sourceGroup = sourceGroups.get(tool.source.pluginName)
    if (!sourceGroup) {
      sourceGroup = { source: tool.source, categories: new Map() }
      sourceGroups.set(tool.source.pluginName, sourceGroup)
    }

    const categoryTools = sourceGroup.categories.get(tool.category) ?? []
    categoryTools.push(tool)
    sourceGroup.categories.set(tool.category, categoryTools)
  }

  return [...sourceGroups.values()].map((group) => ({
    source: group.source,
    categories: [...group.categories].map(([category, categoryTools]) => ({
      category,
      label: toolCategoryLabel(category),
      tools: categoryTools,
    })),
    toolCount: [...group.categories.values()].reduce(
      (count, categoryTools) => count + categoryTools.length,
      0,
    ),
  }))
}
