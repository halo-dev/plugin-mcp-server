import { definePlugin } from '@halo-dev/ui-shared'
import { markRaw } from 'vue'
import McpLineIcon from '~icons/mingcute/mcp-line'

export default definePlugin({
  routes: [
    {
      parentName: 'Root',
      route: {
        path: '/mcp',
        name: 'McpServer',
        component: () => import('./views/McpView.vue'),
        meta: {
          title: 'MCP 管理',
          permissions: ['*'],
          menu: {
            name: 'MCP 管理',
            group: 'tool',
            icon: markRaw(McpLineIcon),
            priority: 50,
          },
        },
      },
    },
  ],
})
