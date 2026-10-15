<template>
  <div class="container">
    <div class="page-head">
      <div>
        <h1 class="page-title">职位广场</h1>
        <p class="page-sub">按关键词与城市筛选职位，一键投递，后端自动完成限流、幂等与匹配打分</p>
      </div>
    </div>

    <div class="card">
      <div class="toolbar">
        <input class="input" v-model.trim="keyword" placeholder="关键词，例如 Java" @keyup.enter="search" />
        <input class="input" v-model.trim="city" placeholder="城市，例如 北京" @keyup.enter="search" />
        <button class="btn btn-primary" @click="search" :disabled="loading">搜索</button>
        <button class="btn btn-outline" @click="reset">重置</button>
      </div>
    </div>

    <div class="alert alert-success" v-if="successMsg">{{ successMsg }}</div>
    <div class="alert alert-error" v-if="errorMsg">
      <div>{{ errorMsg }}</div>
      <router-link v-if="needResume" to="/upload" class="btn btn-outline btn-sm" style="margin-top: 8px">去上传简历</router-link>
    </div>

    <div class="loading" v-if="loading">正在加载职位…</div>

    <div v-else>
      <div class="job-card" v-for="job in list" :key="job.id">
        <div class="job-main">
          <div class="job-title-row">
            <span class="job-title">{{ job.title }}</span>
            <span class="job-salary">{{ job.salaryMin }}-{{ job.salaryMax }}K</span>
          </div>
          <div class="job-meta">
            <span>城市：{{ job.city }}</span>
            <span>招聘人数：{{ job.headCount }} 人</span>
            <span>发布人：HR #{{ job.hrId }}</span>
            <span>发布时间：{{ formatTime(job.createTime) }}</span>
          </div>
          <div class="tags">
            <span class="tag" v-for="skill in skillList(job.skills)" :key="skill">{{ skill }}</span>
          </div>
        </div>
        <div class="job-side">
          <button class="btn btn-primary btn-sm" :disabled="applyingId === job.id" @click="apply(job)">
            {{ applyingId === job.id ? '投递中…' : '投递' }}
          </button>
          <span class="muted" style="font-size: 12px; color: #9ca3af">职位编号 #{{ job.id }}</span>
        </div>
      </div>

      <div class="empty" v-if="!list.length">没有符合条件的职位，换个关键词或城市试试</div>

      <div class="pagination" v-if="total > 0">
        <button class="btn btn-outline btn-sm" :disabled="page <= 1" @click="changePage(page - 1)">上一页</button>
        <span>第 {{ page }} / {{ totalPages }} 页，共 {{ total }} 个职位</span>
        <button class="btn btn-outline btn-sm" :disabled="page >= totalPages" @click="changePage(page + 1)">下一页</button>
      </div>
    </div>
  </div>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { api } from '../api.js'

const PAGE_SIZE = 10

const keyword = ref('')
const city = ref('')
const page = ref(1)
const total = ref(0)
const list = ref([])
const loading = ref(false)
const errorMsg = ref('')
const successMsg = ref('')
const needResume = ref(false)
const applyingId = ref(null)

const totalPages = computed(() => Math.max(1, Math.ceil(total.value / PAGE_SIZE)))

// 技能字段是逗号分隔字符串，这里拆成标签数组
function skillList(skills) {
  if (!skills) {
    return []
  }
  return String(skills).split(',').map(function (item) {
    return item.trim()
  }).filter(function (item) {
    return item.length > 0
  })
}

function formatTime(value) {
  if (!value) {
    return '—'
  }
  return String(value).replace('T', ' ').slice(0, 16)
}

async function load() {
  loading.value = true
  try {
    const data = await api.jobList({
      keyword: keyword.value,
      city: city.value,
      page: page.value,
      size: PAGE_SIZE
    })
    list.value = (data && data.list) || []
    total.value = (data && data.total) || 0
  } catch (e) {
    errorMsg.value = e.message
    list.value = []
    total.value = 0
  } finally {
    loading.value = false
  }
}

function search() {
  page.value = 1
  errorMsg.value = ''
  successMsg.value = ''
  needResume.value = false
  load()
}

function reset() {
  keyword.value = ''
  city.value = ''
  search()
}

function changePage(next) {
  if (next < 1 || next > totalPages.value) {
    return
  }
  page.value = next
  errorMsg.value = ''
  successMsg.value = ''
  needResume.value = false
  load()
}

// 投递：成功显示后端 message，失败显示后端原文
async function apply(job) {
  applyingId.value = job.id
  errorMsg.value = ''
  successMsg.value = ''
  needResume.value = false
  try {
    const data = await api.apply(job.id)
    successMsg.value = (data && data.message) || '投递成功'
  } catch (e) {
    errorMsg.value = e.message
    if (e.message && e.message.indexOf('请先上传简历') >= 0) {
      needResume.value = true
    }
  } finally {
    applyingId.value = null
  }
}

onMounted(load)
</script>
