<template>
  <div class="container">
    <div class="page-head">
      <div>
        <h1 class="page-title">站内信</h1>
        <p class="page-sub">投递进度、状态变更与系统通知都会在这里推送，未读消息加粗显示</p>
      </div>
      <button class="btn btn-outline btn-sm" @click="load">刷新</button>
    </div>

    <div class="alert alert-error" v-if="errorMsg">{{ errorMsg }}</div>

    <div class="card">
      <div class="card-title">
        全部消息<span class="muted">共 {{ list.length }} 条，未读 {{ unreadCount }} 条</span>
      </div>

      <div class="loading" v-if="loading">正在加载站内信…</div>

      <div v-else-if="list.length">
        <div class="notice" :class="{ unread: item.readFlag === 0 }" v-for="item in list" :key="item.id">
          <div class="notice-title">
            <span class="dot" v-if="item.readFlag === 0"></span>
            <span>{{ item.title }}</span>
            <span class="tag" v-if="item.type">{{ item.type }}</span>
          </div>
          <div class="notice-content">{{ item.content }}</div>
          <div class="notice-time">
            {{ formatTime(item.createTime) }}
            <template v-if="item.readFlag === 0"> · 未读</template>
            <template v-else> · 已读</template>
          </div>
        </div>
      </div>

      <div class="empty" v-else>暂时没有站内信</div>
    </div>
  </div>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { api } from '../api.js'

const list = ref([])
const loading = ref(false)
const errorMsg = ref('')

const unreadCount = computed(() => list.value.filter(function (item) {
  return item.readFlag === 0
}).length)

function formatTime(value) {
  if (!value) {
    return '—'
  }
  return String(value).replace('T', ' ').slice(0, 16)
}

async function load() {
  loading.value = true
  errorMsg.value = ''
  try {
    const data = await api.notifications()
    list.value = data || []
  } catch (e) {
    errorMsg.value = e.message
    list.value = []
  } finally {
    loading.value = false
  }
}

onMounted(load)
</script>
