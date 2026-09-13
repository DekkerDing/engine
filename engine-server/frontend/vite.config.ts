import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

// ============================================================
// Vite 配置 — 开发态与生产态的分流都在这里
// ------------------------------------------------------------
// 【教学注释 · 两个构建形态】
// 开发态（npm run dev）:
//   页面跑在 :5173，/api 请求经 vite devServer 代理转发到网关 :8090——
//   好处：改前端代码热更新秒级生效，不用重新构建 Java jar。
// 生产态（npm run build → dist/ → 复制进 engine-gateway.jar 的 static/）:
//   页面与 /api 同源（都是 :8090），浏览器直接发相对路径 /api/**。
// ============================================================
export default defineConfig({
  // 生产产物用绝对路径 /assets/...（由网关同源承载）；
  // 不用 './' 相对路径——SPA 刷新 /search 时相对路径会解析成 /search/assets
  base: '/',
  plugins: [react()],
  server: {
    port: 5173,
    // 开发代理：/api → 网关。只写一条规则，后端地址变了改这里
    proxy: {
      '/api': {
        target: 'http://127.0.0.1:8090',
        changeOrigin: true,
      },
    },
  },
  build: {
    // 产物目录：默认 dist/（copyFrontendDist 任务从这里取）
    outDir: 'dist',
    // 生产构建报 TS 错误直接失败（npm run build 已含 tsc --noEmit，双保险）
    sourcemap: false,
  },
})
