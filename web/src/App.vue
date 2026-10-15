<template>
  <div class="app">
    <!-- 登录后显示深色顶部导航，未登录时只显示内容区 -->
    <header class="topbar" v-if="logged">
      <div class="topbar-inner">
        <router-link to="/jobs" class="logo">
          <span class="logo-mark">OM</span>
          <span>OfferMate 智能招聘平台</span>
        </router-link>

        <nav class="nav">
          <router-link to="/jobs">职位广场</router-link>
          <router-link to="/upload">文件上传</router-link>
          <router-link to="/my-applications">我的投递</router-link>
          <router-link to="/notifications">站内信</router-link>
          <router-link to="/ai">AI 助手</router-link>
          <router-link to="/hr" v-if="isHr">招聘工作台</router-link>
        </nav>

        <div class="user-area">
          <span class="user-name">{{ nickname }}<template v-if="roleText">（{{ roleText }}）</template></span>
          <button class="btn btn-ghost btn-sm" @click="logout">退出登录</button>
        </div>
      </div>
    </header>

    <main class="main" :class="{ 'main-plain': !logged }">
      <router-view />
    </main>
  </div>
</template>

<script setup>
import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { getToken, getUser, clearSession } from './api.js'

const route = useRoute()
const router = useRouter()

// 依赖 route.fullPath，路由变化时重新判断登录态
const logged = computed(() => {
  const current = route.fullPath
  return Boolean(current) && Boolean(getToken())
})

const nickname = computed(() => {
  const user = getUser()
  return (user && user.nickname) || '未命名用户'
})

const role = computed(() => {
  const user = getUser()
  return (user && user.role) || ''
})

const roleText = computed(() => {
  if (role.value === 'SEEKER') {
    return '求职者'
  }
  if (role.value === 'HR') {
    return '招聘方'
  }
  if (role.value === 'ADMIN') {
    return '管理员'
  }
  return ''
})

const isHr = computed(() => role.value === 'HR' || role.value === 'ADMIN')

// 退出登录：清空 localStorage 并回到登录页
function logout() {
  clearSession()
  router.push('/login')
}
</script>
