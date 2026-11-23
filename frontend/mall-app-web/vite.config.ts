import { defineConfig } from 'vite'
import uni from '@dcloudio/vite-plugin-uni'

// https://vitejs.dev/config/
export default defineConfig({
  server: {
    host: '127.0.0.1',
    port: 15174,
    strictPort: true,
    fs: {
      deny: ['.env', '.env.*', '*.{crt,pem}', '**/.git/**', '**/.runtime/**'],
    },
    proxy: {
      '/api': {
        target: 'http://127.0.0.1:18201',
        changeOrigin: true,
        rewrite: (path) => path.replace(/^\/api/, ''),
      },
    },
  },
  build: {
    // 开发阶段启用源码映射：https://uniapp.dcloud.net.cn/tutorial/migration-to-vue3.html#需主动开启-sourcemap
    sourcemap: process.env.NODE_ENV === 'development',
  },
  plugins: [
    {
      name: 'deny-private-files',
      enforce: 'pre',
      configureServer(server) {
        server.middlewares.use((request, response, next) => {
          try {
            const pathname = decodeURIComponent((request.url || '/').split('?')[0]!).toLowerCase()
            const privateSegment = pathname.split(/[\\/]/).some(segment =>
              segment === '.runtime' || segment === '.git' || segment === '.env' || segment.startsWith('.env.'),
            )
            if (privateSegment || /\.(crt|pem)$/.test(pathname)) {
              response.statusCode = 403
              response.end('Forbidden')
              return
            }
            next()
          } catch {
            response.statusCode = 400
            response.end('Bad request')
          }
        })
      },
    },
    uni(),
  ],
})
