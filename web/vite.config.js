import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

// Vite 配置：开发端口 5273，/api 反向代理到本地后端 18080
export default defineConfig({
  plugins: [vue()],
  server: {
    port: 5273,
    proxy: {
      '/api': {
        target: 'http://localhost:18080',
        changeOrigin: true
      }
    }
  }
})
