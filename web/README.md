# OfferMate Web 前端

OfferMate 智能招聘平台的最小可演示前端（Vue 3 + Vite），覆盖登录注册、职位广场、分片上传、投递记录、站内信、AI 助手、招聘工作台七个页面。

## 环境要求

- Node.js 18 及以上
- 后端服务已启动在 http://localhost:18080

## 启动步骤

```bash
# 1. 安装依赖（国内网络慢时可加镜像）
npm install
# npm install --registry=https://registry.npmmirror.com
# 2. 启动开发服务器（默认 http://localhost:5273）
npm run dev

# 3. 打包构建，产物在 dist/ 目录
npm run build

# 4. 本地预览构建产物
npm run preview
```

## 说明

- 路由使用 hash 模式，刷新页面不会 404。
- 开发环境由 Vite 代理 `/api` 到 `http://localhost:18080`，前端代码里不需要写死后端地址。
- 登录成功后 token 存在 localStorage，退出登录会清除。
