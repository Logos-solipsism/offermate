<template>
  <div class="container">
    <div class="page-head">
      <div>
        <h1 class="page-title">我的投递</h1>
        <p class="page-sub">投递记录、匹配分与招聘方处理进度一览</p>
      </div>
      <button class="btn btn-outline btn-sm" @click="load">刷新</button>
    </div>

    <div class="alert alert-error" v-if="errorMsg">{{ errorMsg }}</div>

    <div class="card">
      <div class="loading" v-if="loading">正在加载投递记录…</div>
      <table class="table" v-else-if="list.length">
        <thead>
          <tr>
            <th>职位名称</th>
            <th>状态</th>
            <th>匹配分</th>
            <th>投递时间</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="item in list" :key="item.applicationId">
            <td>{{ titles[item.jobId] || ('职位 #' + item.jobId) }}</td>
            <td><span class="badge" :class="statusClass(item.status)">{{ statusText(item.status) }}</span></td>
            <td>{{ item.matchScore === null || item.matchScore === undefined ? '—' : item.matchScore }}</td>
            <td>{{ formatTime(item.applyTime) }}</td>
          </tr>
        </tbody>
      </table>
      <div class="empty" v-else>还没有投递记录，去职位广场看看吧</div>
    </div>
  </div>
</template>

<script setup>
import { onMounted, reactive, ref } from 'vue'
import { api } from '../api.js'

const list = ref([])
const titles = reactive({})
const loading = ref(false)
const errorMsg = ref('')

// 六个状态的中文与配色
const STATUS_MAP = {
  APPLIED: { text: '已投递', cls: 'badge-blue' },
  VIEWED: { text: '已查看', cls: 'badge-cyan' },
  INTERVIEW: { text: '面试邀约', cls: 'badge-purple' },
  OFFER: { text: '已录用', cls: 'badge-green' },
  REJECTED: { text: '不合适', cls: 'badge-red' },
  WITHDRAWN: { text: '已撤回', cls: 'badge-gray' }
}

function statusText(status) {
  const hit = STATUS_MAP[status]
  return hit ? hit.text : (status || '—')
}

function statusClass(status) {
  const hit = STATUS_MAP[status]
  return hit ? hit.cls : 'badge-gray'
}

function formatTime(value) {
  if (!value) {
    return '—'
  }
  return String(value).replace('T', ' ').slice(0, 16)
}

// 用 jobId 反查职位名称，失败时显示“职位 #id”
async function loadTitle(jobId) {
  if (titles[jobId] !== undefined) {
    return
  }
  titles[jobId] = '加载中…'
  try {
    const job = await api.jobDetail(jobId)
    titles[jobId] = (job && job.title) || ('职位 #' + jobId)
  } catch (e) {
    titles[jobId] = '职位 #' + jobId
  }
}

async function load() {
  loading.value = true
  errorMsg.value = ''
  try {
    const data = await api.myApplications()
    list.value = data || []
    list.value.forEach(function (item) {
      loadTitle(item.jobId)
    })
  } catch (e) {
    errorMsg.value = e.message
    list.value = []
  } finally {
    loading.value = false
  }
}

onMounted(load)
</script>
