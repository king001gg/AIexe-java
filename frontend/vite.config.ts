import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import tailwindcss from '@tailwindcss/vite'

// 端口 3000 是后端 CORS 白名单里的一个（见 java: LoggingConfig#addCorsMappings）。
// 但默认还是走 Vite 代理：浏览器请求 /api/* 打到 3000，由 Vite 转发到 8080，
// 对浏览器而言全程同源，**根本不触发 CORS 预检**。
// 这样即使后端白名单改了、或者你把后端换到别的端口，前端也不用动。
export default defineConfig({
  plugins: [vue(), tailwindcss()],
  server: {
    port: 3000,
    strictPort: true,
    proxy: {
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true,
        // SSE 必须关掉代理层的缓冲，否则 token 会被攒成一坨再吐出来，
        // 流式打字效果直接消失。
        configure: (proxy) => {
          proxy.on('proxyRes', (proxyRes) => {
            if (String(proxyRes.headers['content-type']).includes('text/event-stream')) {
              delete proxyRes.headers['content-encoding']
            }
          })
        },
      },
    },
  },
  preview: { port: 3000, strictPort: true },
})
