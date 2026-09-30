import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

export default defineConfig({
  plugins: [vue()],
  build: {
    // 直接产出到后端工程的静态资源目录（两个工程平级：fast-agent / fast-agent-ui），
    // 随 jar 一起走
    outDir: '../fast-agent/src/main/resources/static',
    // 关掉自动清空：带 safe-delete 钩子的环境会把递归删除拦下来导致构建失败，
    // 改由根目录的 build.ps1 负责清理旧产物
    emptyOutDir: false,
    // JavaFX WebView 的 WebKit 对 ES2020 支持良好，降到 es2019 更保险
    target: 'es2019',
    chunkSizeWarningLimit: 1500
  },
  server: {
    port: 5173,
    proxy: {
      // 浏览器里 `npm run dev` 调试时把 API 转到本机后端。
      // 桌面版后端默认随机端口，调试时请固定端口启动：
      //   mvn spring-boot:run "-Dspring-boot.run.arguments=--server.port=18080"
      '/api': 'http://127.0.0.1:18080'
    }
  }
})
