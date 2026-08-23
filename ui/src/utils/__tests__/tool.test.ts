import type { McpTool } from '@/api'
import { describe, expect, it } from 'vitest'
import { groupTools } from '../tool'

function tool(name: string, category: string, pluginName = 'PluginMcpServer'): McpTool {
  return {
    name,
    category,
    available: true,
    destructive: false,
    inputSchema: { type: 'object', properties: {} },
    readOnly: true,
    source: {
      pluginName,
      displayName: pluginName,
      type: pluginName === 'PluginMcpServer' ? 'BUILT_IN' : 'PLUGIN',
    },
  }
}

describe('groupTools', () => {
  it('groups tools by source and business category', () => {
    const groups = groupTools([
      tool('halo_list_posts', 'POST'),
      tool('halo_get_post', 'POST'),
      tool('halo_list_comments', 'COMMENT'),
      tool('demo/export', 'PLUGIN', 'PluginDemo'),
    ])

    expect(groups).toHaveLength(2)
    const builtInGroup = groups[0]
    const pluginGroup = groups[1]
    expect(builtInGroup).toBeDefined()
    expect(pluginGroup).toBeDefined()
    if (!builtInGroup || !pluginGroup) {
      throw new Error('Expected built-in and plugin tool groups')
    }

    expect(builtInGroup).toMatchObject({
      source: { pluginName: 'PluginMcpServer' },
      toolCount: 3,
      categories: [
        { category: 'POST', label: '文章管理' },
        { category: 'COMMENT', label: '评论管理' },
      ],
    })
    const postCategory = builtInGroup.categories[0]
    expect(postCategory).toBeDefined()
    expect(postCategory?.tools.map(({ name }) => name)).toEqual([
      'halo_list_posts',
      'halo_get_post',
    ])
    expect(pluginGroup).toMatchObject({
      source: { pluginName: 'PluginDemo' },
      toolCount: 1,
      categories: [{ category: 'PLUGIN', label: '插件工具' }],
    })
  })
})
