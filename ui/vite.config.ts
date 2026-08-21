import { fileURLToPath, URL } from 'node:url'

import { viteConfig } from '@halo-dev/ui-plugin-bundler-kit/vite'
import UnoCSS from 'unocss/vite'
import Icons from 'unplugin-icons/vite'

export default viteConfig({
  vite: {
    resolve: {
      alias: {
        '@': fileURLToPath(new URL('./src', import.meta.url)),
      },
    },
    plugins: [UnoCSS({ mode: 'vue-scoped' }), Icons({ compiler: 'vue3' })],
  },
})
