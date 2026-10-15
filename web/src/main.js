import { createApp } from 'vue'
import App from './App.vue'
import router from './router.js'
import './styles.css'

// 应用入口：挂载路由与全局样式
createApp(App).use(router).mount('#app')
