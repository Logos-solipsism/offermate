<template>
  <div class="container">
    <div class="page-head">
      <div>
        <h1 class="page-title">文件上传</h1>
        <p class="page-sub">秒传 / 断点续传 / 分片并发上传，合并后交给消息队列异步解析（分片大小 5 MB，并发 3 路）</p>
      </div>
      <button class="btn btn-outline btn-sm" @click="loadMyFiles">刷新我的文件</button>
    </div>

    <div class="card">
      <div class="card-title">第一步：选择文件</div>
      <div class="toolbar">
        <input class="input" style="max-width: 320px" type="file" @change="onFileChange" :disabled="busy" />
        <button class="btn btn-primary" :disabled="!file || busy" @click="startUpload">
          {{ busy ? '上传中…' : '开始上传' }}
        </button>
      </div>

      <div class="kv" style="margin-top: 14px" v-if="file">
        <div class="kv-item">文件名<strong>{{ file.name }}</strong></div>
        <div class="kv-item">文件大小<strong>{{ formatSize(file.size) }}</strong></div>
        <div class="kv-item" v-if="fileMd5">文件指纹（MD5）<strong>{{ fileMd5 }}</strong></div>
      </div>
    </div>

    <!-- 计算文件指纹进度 -->
    <div class="card" v-if="phase === 'hashing' || (md5Percent > 0 && phase !== 'idle')">
      <div class="card-title">正在计算文件指纹 {{ md5Percent }}%</div>
      <div class="progress">
        <div class="progress-bar" :style="{ width: md5Percent + '%' }"></div>
      </div>
      <div class="progress-meta">
        <span>使用 spark-md5 按 5 MB 分片增量计算，避免一次性把大文件读进内存</span>
        <span>{{ md5Percent }}%</span>
      </div>
    </div>

    <!-- 上传分支提示：秒传 / 断点续传 / 新建任务，截图重点 -->
    <div v-if="branch === 'instant'" class="banner banner-success">
      秒传命中，本次上传 0 字节
      <span class="banner-sub">服务端已存在相同 MD5 的文件，初始化接口直接返回已有 fileId，无需再传任何分片。</span>
    </div>

    <div v-if="branch === 'resume'" class="banner banner-info">
      断点续传：服务端已有第 {{ serverPartsText }} 片，只需补传 {{ pendingCount }} 片
      <span class="banner-sub">共 {{ totalParts }} 片，其中 {{ serverPartsCount }} 片已存在于服务端；中断后重新选择同一个文件即可走这条路。</span>
    </div>

    <div v-if="branch === 'new'" class="banner banner-neutral">
      新建上传任务
      <span class="banner-sub">服务端没有该文件的分片记录，将按 5 MB 切片后并发 3 路上传全部分片。</span>
    </div>

    <!-- 分片上传进度 -->
    <div class="card" v-if="totalParts > 0 && branch !== 'instant'">
      <div class="card-title">第二步：分片上传</div>
      <div class="progress">
        <div class="progress-bar" :class="{ 'progress-bar-green': uploadPercent >= 100 }" :style="{ width: uploadPercent + '%' }"></div>
      </div>
      <div class="progress-meta">
        <span>已完成 {{ doneParts }} / {{ totalParts }} 片</span>
        <span>已上传 {{ formatSize(uploadedBytes) }} / {{ formatSize(file ? file.size : 0) }}（{{ uploadedMb }} MB）</span>
        <span>并发 {{ CONCURRENCY }} 路</span>
      </div>
    </div>

    <!-- 合并与异步解析 -->
    <div v-if="mergeDone" class="banner banner-info" style="margin-top: 16px">
      分片已合并，正在异步解析（后端 MQ 消费中）…
      <span class="banner-sub">合并接口已返回 fileId = {{ fileId }}，解析由 RocketMQ 异步消费，前端每 2 秒轮询一次解析状态，最长 60 秒。</span>
    </div>

    <div class="card" v-if="merging || mergeDone">
      <div class="card-title">
        第三步：解析状态
        <span class="muted badge" :class="parseBadgeClass">{{ parseStatusText }}</span>
      </div>

      <div class="alert alert-error" v-if="parseError">{{ parseError }}</div>

      <div class="loading" v-if="merging || parsing">正在轮询解析结果…（已等待 {{ waitedSeconds }} 秒 / 最长 60 秒）</div>

      <div v-if="parsedFile">
        <div class="kv">
          <div class="kv-item">姓名<strong>{{ parsedFile.name || '—' }}</strong></div>
          <div class="kv-item">学历<strong>{{ parsedFile.education || '—' }}</strong></div>
          <div class="kv-item">学校<strong>{{ parsedFile.school || '—' }}</strong></div>
        </div>
        <div class="tags" v-if="parsedSkills.length">
          <span class="tag tag-green" v-for="skill in parsedSkills" :key="skill">{{ skill }}</span>
        </div>
      </div>
    </div>

    <div class="alert alert-error" v-if="errorMsg">{{ errorMsg }}</div>

    <!-- 已上传文件列表 -->
    <div class="card">
      <div class="card-title">我的文件<span class="muted">共 {{ myFiles.length }} 个</span></div>
      <div class="loading" v-if="filesLoading">正在加载…</div>
      <table class="table" v-else-if="myFiles.length">
        <thead>
          <tr>
            <th>文件名</th>
            <th>大小</th>
            <th>上传状态</th>
            <th>解析状态</th>
            <th>姓名</th>
            <th>学历</th>
            <th>技能</th>
            <th>创建时间</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="item in myFiles" :key="item.fileId">
            <td>{{ item.fileName }}</td>
            <td>{{ formatSize(item.size) }}</td>
            <td><span class="badge" :class="fileStatusClass(item.status)">{{ fileStatusText(item.status) }}</span></td>
            <td><span class="badge" :class="parseStatusClass(item.parseStatus)">{{ parseStatusText2(item.parseStatus) }}</span></td>
            <td>{{ item.name || '—' }}</td>
            <td>{{ item.education || '—' }}</td>
            <td>
              <span class="tag" v-for="skill in normalizeSkills(item.skills)" :key="skill">{{ skill }}</span>
              <span v-if="!normalizeSkills(item.skills).length">—</span>
            </td>
            <td>{{ formatTime(item.createTime) }}</td>
          </tr>
        </tbody>
      </table>
      <div class="empty" v-else>还没有上传过文件</div>
    </div>
  </div>
</template>

<script setup>
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import SparkMD5 from 'spark-md5'
import { api } from '../api.js'

// 分片大小固定 5 MB，并发 3 路，最长轮询 60 秒
const CHUNK_SIZE = 5 * 1024 * 1024
const CONCURRENCY = 3
const POLL_INTERVAL = 2000
const MAX_POLL_MS = 60000

const file = ref(null)
const fileMd5 = ref('')
const md5Percent = ref(0)
const busy = ref(false)
const phase = ref('idle')
const branch = ref('')
const uploadId = ref('')
const fileId = ref(null)
const totalParts = ref(0)
const doneParts = ref(0)
const pendingCount = ref(0)
const uploadedBytes = ref(0)
const serverParts = ref([])
const merging = ref(false)
const mergeDone = ref(false)
const parsing = ref(false)
const parsedFile = ref(null)
const parseError = ref('')
const errorMsg = ref('')
const waitedSeconds = ref(0)

const myFiles = ref([])
const filesLoading = ref(false)

let pollTimer = null
let pollStartAt = 0
let secondsTimer = null

const uploadPercent = computed(() => {
  if (!totalParts.value) {
    return 0
  }
  return Math.min(100, Math.round((doneParts.value / totalParts.value) * 100))
})

const uploadedMb = computed(() => (uploadedBytes.value / 1024 / 1024).toFixed(1))

const serverPartsCount = computed(() => serverParts.value.length)

const serverPartsText = computed(() => serverParts.value.join(','))

const parsedSkills = computed(() => normalizeSkills(parsedFile.value ? parsedFile.value.skills : null))

const parseStatusText = computed(() => {
  if (parsing.value || merging.value) {
    return '解析中'
  }
  if (parsedFile.value && parsedFile.value.parseStatus === 'DONE') {
    return '解析完成'
  }
  if (parseError.value) {
    return '解析失败'
  }
  return '等待中'
})

const parseBadgeClass = computed(() => {
  if (parsedFile.value && parsedFile.value.parseStatus === 'DONE') {
    return 'badge-green'
  }
  if (parseError.value) {
    return 'badge-red'
  }
  return 'badge-blue'
})

// 把字节数格式化成易读的 KB / MB
function formatSize(bytes) {
  const value = Number(bytes) || 0
  if (value < 1024) {
    return value + ' B'
  }
  if (value < 1024 * 1024) {
    return (value / 1024).toFixed(1) + ' KB'
  }
  return (value / 1024 / 1024).toFixed(1) + ' MB'
}

function formatTime(value) {
  if (!value) {
    return '—'
  }
  return String(value).replace('T', ' ').slice(0, 16)
}

// skills 可能是数组，也可能是逗号分隔字符串
function normalizeSkills(skills) {
  if (!skills) {
    return []
  }
  if (Array.isArray(skills)) {
    return skills.filter(function (item) {
      return item
    })
  }
  return String(skills).split(',').map(function (item) {
    return item.trim()
  }).filter(function (item) {
    return item.length > 0
  })
}

function fileStatusText(status) {
  const map = { UPLOADING: '上传中', MERGING: '合并中', DONE: '已完成', FAILED: '失败' }
  return map[status] || status || '—'
}

function fileStatusClass(status) {
  if (status === 'DONE') {
    return 'badge-green'
  }
  if (status === 'FAILED') {
    return 'badge-red'
  }
  return 'badge-blue'
}

function parseStatusText2(status) {
  const map = { PENDING: '排队中', PARSING: '解析中', DONE: '解析完成', FAILED: '解析失败' }
  return map[status] || status || '—'
}

function parseStatusClass(status) {
  if (status === 'DONE') {
    return 'badge-green'
  }
  if (status === 'FAILED') {
    return 'badge-red'
  }
  if (status === 'PARSING') {
    return 'badge-orange'
  }
  return 'badge-gray'
}

// 选择文件：重置上一轮状态
function onFileChange(event) {
  const picked = event.target.files && event.target.files[0]
  stopPolling()
  phase.value = 'idle'
  branch.value = ''
  fileMd5.value = ''
  md5Percent.value = 0
  uploadId.value = ''
  fileId.value = null
  totalParts.value = 0
  doneParts.value = 0
  pendingCount.value = 0
  uploadedBytes.value = 0
  serverParts.value = []
  merging.value = false
  mergeDone.value = false
  parsing.value = false
  parsedFile.value = null
  parseError.value = ''
  errorMsg.value = ''
  waitedSeconds.value = 0
  file.value = picked || null
}

// 用 spark-md5 按 5 MB 分片增量计算整个文件的 MD5，并实时上报进度
function computeMd5(target) {
  return new Promise(function (resolve, reject) {
    const spark = new SparkMD5.ArrayBuffer()
    const reader = new FileReader()
    const chunks = Math.ceil(target.size / CHUNK_SIZE) || 0
    let index = 0

    if (chunks === 0) {
      resolve(spark.end())
      return
    }

    function readNext() {
      const start = index * CHUNK_SIZE
      const end = Math.min(start + CHUNK_SIZE, target.size)
      reader.readAsArrayBuffer(target.slice(start, end))
    }

    reader.onload = function (event) {
      spark.append(event.target.result)
      index += 1
      md5Percent.value = Math.round((index / chunks) * 100)
      if (index < chunks) {
        readNext()
      } else {
        resolve(spark.end())
      }
    }

    reader.onerror = function () {
      reject(new Error('读取文件失败，请重试'))
    }

    readNext()
  })
}

// 三路并发上传分片
async function uploadChunks(parts) {
  let cursor = 0

  async function worker() {
    while (cursor < parts.length) {
      const partNumber = parts[cursor]
      cursor += 1
      const start = (partNumber - 1) * CHUNK_SIZE
      const end = Math.min(start + CHUNK_SIZE, file.value.size)
      const blob = file.value.slice(start, end)

      const form = new FormData()
      // 文件字段名必须是 file
      form.append('file', blob, file.value.name)

      await api.uploadChunk(uploadId.value, partNumber, form)

      doneParts.value += 1
      uploadedBytes.value = Math.min(file.value.size, uploadedBytes.value + blob.size)
    }
  }

  const workerCount = Math.min(CONCURRENCY, parts.length)
  const workers = []
  for (let i = 0; i < workerCount; i += 1) {
    workers.push(worker())
  }
  await Promise.all(workers)
}

// 主流程：算指纹 -> init 判定分支 -> 补传分片 -> merge -> 轮询解析
async function startUpload() {
  if (!file.value) {
    return
  }
  errorMsg.value = ''
  parseError.value = ''
  parsedFile.value = null
  mergeDone.value = false
  busy.value = true
  md5Percent.value = 0

  try {
    phase.value = 'hashing'
    const md5 = await computeMd5(file.value)
    fileMd5.value = md5

    const total = Math.ceil(file.value.size / CHUNK_SIZE)
    totalParts.value = total

    const initResult = await api.uploadInit({
      fileName: file.value.name,
      fileMd5: md5,
      size: file.value.size
    })

    fileId.value = initResult.fileId
    const exists = (initResult.uploadedParts || []).slice().sort(function (a, b) {
      return a - b
    })
    serverParts.value = exists
    doneParts.value = exists.length
    uploadedBytes.value = Math.min(file.value.size, exists.length * CHUNK_SIZE)

    // 分支 a：秒传命中，一个字节都不用传
    if (initResult.instantUpload) {
      branch.value = 'instant'
      doneParts.value = total
      uploadedBytes.value = file.value.size
      phase.value = 'parsing'
      loadMyFiles()
      startPolling()
      return
    }

    // 分支 b：断点续传，只补缺失分片；分支 c：全新任务
    if (exists.length > 0) {
      branch.value = 'resume'
    } else {
      branch.value = 'new'
    }

    uploadId.value = initResult.uploadId

    const pending = []
    for (let i = 1; i <= total; i += 1) {
      if (exists.indexOf(i) < 0) {
        pending.push(i)
      }
    }
    pendingCount.value = pending.length

    phase.value = 'uploading'
    await uploadChunks(pending)

    phase.value = 'merging'
    merging.value = true
    await api.uploadMerge({ uploadId: uploadId.value, totalParts: total })
    merging.value = false
    mergeDone.value = true
    loadMyFiles()
    startPolling()
  } catch (e) {
    errorMsg.value = e.message
    phase.value = 'failed'
    merging.value = false
  } finally {
    busy.value = false
  }
}

// 轮询解析状态：每 2 秒一次，最长 60 秒
function startPolling() {
  stopPolling()
  parsing.value = true
  parseError.value = ''
  pollStartAt = Date.now()
  waitedSeconds.value = 0
  pollOnce()
  pollTimer = setInterval(pollOnce, POLL_INTERVAL)
  secondsTimer = setInterval(function () {
    waitedSeconds.value = Math.round((Date.now() - pollStartAt) / 1000)
  }, 1000)
}

function stopPolling() {
  if (pollTimer) {
    clearInterval(pollTimer)
    pollTimer = null
  }
  if (secondsTimer) {
    clearInterval(secondsTimer)
    secondsTimer = null
  }
  parsing.value = false
}

async function pollOnce() {
  if (!fileId.value) {
    stopPolling()
    return
  }
  if (Date.now() - pollStartAt > MAX_POLL_MS) {
    stopPolling()
    parseError.value = '已轮询 60 秒仍未解析完成，可稍后点击“刷新我的文件”查看最新状态'
    return
  }
  try {
    const list = await api.resumeMine()
    // 轮询结果同时刷新“我的文件”列表，避免重复请求
    myFiles.value = list || []
    const current = (list || []).find(function (item) {
      return String(item.fileId) === String(fileId.value)
    })
    if (!current) {
      return
    }
    if (current.parseStatus === 'DONE') {
      stopPolling()
      parsedFile.value = current
    } else if (current.parseStatus === 'FAILED') {
      stopPolling()
      parseError.value = '解析失败，请重新上传该文件或联系管理员'
    }
  } catch (e) {
    // 轮询失败不打断流程，等下一次
  }
}

async function loadMyFiles() {
  filesLoading.value = true
  try {
    const list = await api.resumeMine()
    myFiles.value = list || []
  } catch (e) {
    // 列表加载失败不影响主流程
  } finally {
    filesLoading.value = false
  }
}

onMounted(loadMyFiles)

onBeforeUnmount(stopPolling)
</script>
