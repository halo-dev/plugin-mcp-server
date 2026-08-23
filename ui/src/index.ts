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
          title: 'MCP 服务',
          permissions: ['*'],
          menu: {
            name: 'MCP 服务',
            group: 'system',
            icon: markRaw(McpLineIcon),
            priority: 101,
          },
        },
      },
    },
  ],
})
