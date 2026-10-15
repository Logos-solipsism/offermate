<template>
  <div class="container">
    <div class="page-head">
      <div>
        <h1 class="page-title">招聘工作台</h1>
        <p class="page-sub">左侧选择我发布的职位，右侧处理候选人投递：更新状态并填写反馈</p>
      </div>
      <button class="btn btn-outline btn-sm" @click="loadJobs">刷新职位</button>
    </div>

    <div class="alert alert-error" v-if="errorMsg">{{ errorMsg }}</div>
    <div class="alert alert-success" v-if="successMsg">{{ successMsg }}</div>

    <div class="split">
      <!-- 左：我发布的职位 -->
      <div class="card">
        <div class="card-title">我发布的职位<span class="muted">共 {{ jobs.length }} 个</span></div>

        <div class="loading" v-if="jobsLoading">正在加载…</div>

        <div v-else-if="jobs.length">
          <div
            class="job-pick"
            :class="{ active: currentJob && currentJob.id === job.id }"
            v-for="job in jobs"
            :key="job.id"
            @click="selectJob(job)"
          >
            <div class="job-pick-title">{{ job.title }}</div>
            <div class="job-pick-meta">{{ job.city }} · {{ job.salaryMin }}-{{ job.salaryMax }}K · 招 {{ job.headCount }} 人</div>
            <div class="job-pick-meta">
              <span class="badge" :class="job.status === 1 ? 'badge-green' : 'badge-gray'">{{ job.status === 1 ? '招聘中' : '已下线' }}</span>
              <span class="badge badge-blue" style="margin-left: 6px">审核 {{ job.auditStatus }}</span>
            </div>
          </div>
        </div>

        <div class="empty" v-else>还没有发布职位</div>
      </div>

      <!-- 右：该职位的投递列表 -->
      <div class="card">
        <div class="card-title">
          投递列表
          <span class="muted" v-if="currentJob">职位：{{ currentJob.title }}</span>
        </div>

        <div class="empty" v-if="!currentJob">请先在左侧选择一个职位</div>

        <div v-else>
          <div class="loading" v-if="applicationsLoading">正在加载投递记录…</div>

          <table class="table" v-else-if="applications.length">
            <thead>
              <tr>
                <th>候选人</th>
                <th>匹配分</th>
                <th>当前状态</th>
                <th>投递时间</th>
                <th>处理</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="item in applications" :key="item.applicationId">
                <td>
                  {{ item.nickname || ('用户 #' + item.userId) }}
                  <div style="color: #9ca3af; font-size: 12px">ID {{ item.userId }}</div>
                </td>
                <td>{{ item.matchScore === null || item.matchScore === undefined ? '—' : item.matchScore }}</td>
                <td><span class="badge" :class="statusClass(item.status)">{{ statusText(item.status) }}</span></td>
                <td>{{ formatTime(item.applyTime) }}</td>
                <td>
                  <div class="form-row">
                    <select class="select" v-model="item.draftStatus">
                      <option value="VIEWED">已查看</option>
                      <option value="INTERVIEW">面试邀约</option>
                      <option value="OFFER">已录用</option>
                      <option value="REJECTED">不合适</option>
                    </select>
                    <input class="input" v-model.trim="item.draftFeedback" placeholder="反馈内容" />
                    <button class="btn btn-primary btn-sm" :disabled="savingId === item.applicationId" @click="save(item)">
                      {{ savingId === item.applicationId ? '保存中…' : '保存' }}
                    </button>
                  </div>
                </td>
              </tr>
            </tbody>
          </table>

          <div class="empty" v-else>该职位还没有收到投递</div>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { onMounted, ref } from 'vue'
import { api } from '../api.js'

const jobs = ref([])
const jobsLoading = ref(false)
const currentJob = ref(null)
const applications = ref([])
const applicationsLoading = ref(false)
const savingId = ref(null)
const errorMsg = ref('')
const successMsg = ref('')

const STATUS_MAP = {
  APPLIED: { text: '已投递', cls: 'badge-blue' },
  VIEWED: { text: '已查看', cls: 'badge-cyan' },
  INTERVIEW: { text: '面试邀约', cls: 'badge-purple' },
  OFFER: { text: '已录用', cls: 'badge-green' },
  REJECTED: { text: '不合适', cls: 'badge-red' },
  WITHDRAWN: { text: '已撤回', cls: 'badge-gray' }
}

// 状态下拉可选值，默认选中当前状态（不在可选范围内则回退为已查看）
const EDITABLE = ['VIEWED', 'INTERVIEW', 'OFFER', 'REJECTED']

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

async function loadJobs() {
  jobsLoading.value = true
  errorMsg.value = ''
  try {
    const data = await api.jobMine()
    jobs.value = data || []
    if (jobs.value.length) {
      selectJob(jobs.value[0])
    }
  } catch (e) {
    errorMsg.value = e.message
    jobs.value = []
  } finally {
    jobsLoading.value = false
  }
}

async function selectJob(job) {
  currentJob.value = job
  applications.value = []
  await loadApplications(job.id)
}

async function loadApplications(jobId) {
  applicationsLoading.value = true
  errorMsg.value = ''
  try {
    const data = await api.jobApplications(jobId)
    applications.value = (data || []).map(function (item) {
      return {
        applicationId: item.applicationId,
        userId: item.userId,
        nickname: item.nickname,
        status: item.status,
        matchScore: item.matchScore,
        hrFeedback: item.hrFeedback,
        applyTime: item.applyTime,
        draftStatus: EDITABLE.indexOf(item.status) >= 0 ? item.status : 'VIEWED',
        draftFeedback: item.hrFeedback || ''
      }
    })
  } catch (e) {
    errorMsg.value = e.message
    applications.value = []
  } finally {
    applicationsLoading.value = false
  }
}

// 保存状态与反馈
async function save(item) {
  savingId.value = item.applicationId
  errorMsg.value = ''
  successMsg.value = ''
  try {
    await api.updateApplicationStatus(item.applicationId, {
      status: item.draftStatus,
      feedback: item.draftFeedback
    })
    successMsg.value = '已更新候选人 ' + (item.nickname || ('用户 #' + item.userId)) + ' 的投递状态'
    if (currentJob.value) {
      await loadApplications(currentJob.value.id)
    }
  } catch (e) {
    errorMsg.value = e.message
  } finally {
    savingId.value = null
  }
}

onMounted(loadJobs)
</script>
