<template>
  <div class="login-page">
    <div class="login-card">
      <div class="login-brand">
        <span class="logo-mark">OM</span>
        <h1>OfferMate 智能招聘平台</h1>
      </div>
      <p class="login-tip">求职者与招聘方的一体化招聘平台：职位投递、智能匹配、AI 助手</p>

      <div class="tabs">
        <button class="tab" :class="{ active: mode === 'login' }" @click="switchMode('login')">登录</button>
        <button class="tab" :class="{ active: mode === 'register' }" @click="switchMode('register')">注册</button>
      </div>

      <div class="alert alert-error" v-if="errorMsg">{{ errorMsg }}</div>
      <div class="alert alert-success" v-if="successMsg">{{ successMsg }}</div>

      <!-- 登录 -->
      <form v-if="mode === 'login'" @submit.prevent="doLogin">
        <div class="field">
          <label class="label">手机号</label>
          <input class="input" v-model.trim="loginForm.phone" placeholder="请输入手机号" autocomplete="username" />
        </div>
        <div class="field">
          <label class="label">密码</label>
          <input class="input" type="password" v-model="loginForm.password" placeholder="请输入密码" autocomplete="current-password" />
        </div>
        <button class="btn btn-primary" style="width: 100%" type="submit" :disabled="loading">
          {{ loading ? '登录中…' : '登录' }}
        </button>
      </form>

      <!-- 注册 -->
      <form v-else @submit.prevent="doRegister">
        <div class="field">
          <label class="label">手机号</label>
          <input class="input" v-model.trim="registerForm.phone" placeholder="例如 13900000001" />
        </div>
        <div class="field">
          <label class="label">密码</label>
          <input class="input" type="password" v-model="registerForm.password" placeholder="请输入密码" />
        </div>
        <div class="field">
          <label class="label">昵称</label>
          <input class="input" v-model.trim="registerForm.nickname" placeholder="例如 小明" />
        </div>
        <div class="field">
          <label class="label">角色</label>
          <select class="select" v-model="registerForm.role">
            <option value="SEEKER">SEEKER 求职者</option>
            <option value="HR">HR 招聘方</option>
            <option value="ADMIN">ADMIN 管理员</option>
          </select>
        </div>
        <button class="btn btn-primary" style="width: 100%" type="submit" :disabled="loading">
          {{ loading ? '提交中…' : '注册' }}
        </button>
      </form>
    </div>
  </div>
</template>

<script setup>
import { reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { api, saveSession } from '../api.js'

const router = useRouter()
const mode = ref('login')
const loading = ref(false)
const errorMsg = ref('')
const successMsg = ref('')

const loginForm = reactive({ phone: '', password: '' })
const registerForm = reactive({ phone: '', password: '', nickname: '', role: 'SEEKER' })

function switchMode(next) {
  mode.value = next
  errorMsg.value = ''
  successMsg.value = ''
}

// 登录成功后按角色跳转：求职者去职位广场，招聘方与管理员去工作台
async function doLogin() {
  errorMsg.value = ''
  successMsg.value = ''
  if (!loginForm.phone || !loginForm.password) {
    errorMsg.value = '请填写手机号和密码'
    return
  }
  loading.value = true
  try {
    const data = await api.login({ phone: loginForm.phone, password: loginForm.password })
    saveSession(data)
    if (data.role === 'SEEKER') {
      router.push('/jobs')
    } else {
      router.push('/hr')
    }
  } catch (e) {
    errorMsg.value = e.message
  } finally {
    loading.value = false
  }
}

// 注册成功后退回登录表单
async function doRegister() {
  errorMsg.value = ''
  successMsg.value = ''
  if (!registerForm.phone || !registerForm.password || !registerForm.nickname) {
    errorMsg.value = '请把手机号、密码、昵称填写完整'
    return
  }
  loading.value = true
  try {
    await api.register({
      phone: registerForm.phone,
      password: registerForm.password,
      nickname: registerForm.nickname,
      role: registerForm.role
    })
    successMsg.value = '注册成功，请使用手机号登录'
    loginForm.phone = registerForm.phone
    loginForm.password = ''
    mode.value = 'login'
  } catch (e) {
    errorMsg.value = e.message
  } finally {
    loading.value = false
  }
}
</script>
