import { createRouter, createWebHashHistory } from 'vue-router'
import { getToken } from './api.js'
import Login from './views/Login.vue'
import Upload from './views/Upload.vue'
import Jobs from './views/Jobs.vue'
import MyApplications from './views/MyApplications.vue'
import Notifications from './views/Notifications.vue'
import AiChat from './views/AiChat.vue'
import Hr from './views/Hr.vue'

// 路由表：除登录页外都需要登录，使用 hash 模式避免刷新 404
const routes = [
  { path: '/', redirect: '/jobs' },
  { path: '/login', name: 'login', component: Login, meta: { isPublic: true } },
  { path: '/jobs', name: 'jobs', component: Jobs },
  { path: '/upload', name: 'upload', component: Upload },
  { path: '/my-applications', name: 'myApplications', component: MyApplications },
  { path: '/notifications', name: 'notifications', component: Notifications },
  { path: '/ai', name: 'aiChat', component: AiChat },
  { path: '/hr', name: 'hr', component: Hr },
  { path: '/:pathMatch(.*)*', redirect: '/jobs' }
]

const router = createRouter({
  history: createWebHashHistory(),
  routes
})

// 全局前置守卫：没登录一律回到登录页
router.beforeEach((to) => {
  const token = getToken()
  if (to.meta.isPublic) {
    if (token && to.path === '/login') {
      return { path: '/jobs' }
    }
    return true
  }
  if (!token) {
    return { path: '/login' }
  }
  return true
})

export default router
