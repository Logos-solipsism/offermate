// 统一请求封装：自动携带 token、自动解析统一响应体、统一错误处理
const TOKEN_KEY = 'offermate_token'
const USER_KEY = 'offermate_user'

// 读取本地 token
export function getToken() {
  return localStorage.getItem(TOKEN_KEY) || ''
}

// 读取本地登录用户信息
export function getUser() {
  try {
    return JSON.parse(localStorage.getItem(USER_KEY) || 'null')
  } catch (e) {
    return null
  }
}

// 登录成功后保存会话
export function saveSession(data) {
  localStorage.setItem(TOKEN_KEY, data.token || '')
  localStorage.setItem(USER_KEY, JSON.stringify({
    userId: data.userId,
    nickname: data.nickname,
    role: data.role
  }))
}

// 退出登录：清空本地会话
export function clearSession() {
  localStorage.removeItem(TOKEN_KEY)
  localStorage.removeItem(USER_KEY)
}

// 拼接查询字符串，空值不参与拼接
function buildQuery(query) {
  if (!query) {
    return ''
  }
  const search = new URLSearchParams()
  Object.keys(query).forEach(function (key) {
    const value = query[key]
    if (value !== undefined && value !== null && value !== '') {
      search.append(key, value)
    }
  })
  const text = search.toString()
  return text ? '?' + text : ''
}

// 核心请求方法：返回统一响应体里的 data 字段
export async function request(path, options) {
  const opts = options || {}
  const method = opts.method || 'GET'
  const url = path + buildQuery(opts.query)

  const headers = {}
  const token = getToken()
  if (token) {
    headers['Authorization'] = 'Bearer ' + token
  }

  let body
  if (opts.body instanceof FormData) {
    // 文件分片交给浏览器自动设置 multipart 边界，不能手动指定 Content-Type
    body = opts.body
  } else if (opts.body !== undefined && opts.body !== null) {
    headers['Content-Type'] = 'application/json'
    body = JSON.stringify(opts.body)
  }

  let response
  try {
    response = await fetch(url, { method: method, headers: headers, body: body })
  } catch (e) {
    throw new Error('网络异常，请确认后端服务已启动（http://localhost:18080）')
  }

  const text = await response.text()
  let payload = null
  if (text) {
    try {
      payload = JSON.parse(text)
    } catch (e) {
      payload = null
    }
  }

  // 登录态失效：清掉 token 并回到登录页
  if (response.status === 401) {
    clearSession()
    if (window.location.hash !== '#/login') {
      window.location.hash = '#/login'
    }
    throw new Error((payload && payload.message) || '登录状态已失效，请重新登录')
  }

  // 限流
  if (response.status === 429) {
    throw new Error((payload && payload.message) || '操作过于频繁，请稍后再试')
  }

  if (!response.ok) {
    throw new Error((payload && payload.message) || ('请求失败，HTTP 状态码 ' + response.status))
  }

  // 统一响应体 { code, message, data }
  if (payload && typeof payload === 'object' && Object.prototype.hasOwnProperty.call(payload, 'code')) {
    if (payload.code !== 0) {
      throw new Error(payload.message || '请求失败')
    }
    return payload.data
  }
  return payload
}

// 按业务拆分的接口集合
export const api = {
  // 1. 注册
  register: function (data) {
    return request('/api/auth/register', { method: 'POST', body: data })
  },
  // 2. 登录
  login: function (data) {
    return request('/api/auth/login', { method: 'POST', body: data })
  },
  // 3. 职位列表
  jobList: function (query) {
    return request('/api/job/list', { query: query })
  },
  // 4. 职位详情
  jobDetail: function (id) {
    return request('/api/job/' + id)
  },
  // 5. HR 我发布的职位
  jobMine: function () {
    return request('/api/job/mine')
  },
  // 6. 我上传的文件（含解析状态）
  resumeMine: function () {
    return request('/api/resume/mine')
  },
  // 7. 初始化上传（秒传 / 断点续传判定）
  uploadInit: function (data) {
    return request('/api/resume/upload/init', { method: 'POST', body: data })
  },
  // 8. 上传单个分片，uploadId 与 partNumber 放在 query 上
  uploadChunk: function (uploadId, partNumber, formData) {
    return request('/api/resume/upload/chunk', {
      method: 'POST',
      query: { uploadId: uploadId, partNumber: partNumber },
      body: formData
    })
  },
  // 9. 合并分片
  uploadMerge: function (data) {
    return request('/api/resume/upload/merge', { method: 'POST', body: data })
  },
  // 10. 投递职位
  apply: function (jobId) {
    return request('/api/application', { method: 'POST', body: { jobId: jobId } })
  },
  // 11. 我的投递
  myApplications: function () {
    return request('/api/application/mine')
  },
  // 12. HR 修改投递状态
  updateApplicationStatus: function (id, data) {
    return request('/api/application/' + id + '/status', { method: 'PUT', body: data })
  },
  // 13. HR 查看某职位的投递列表
  jobApplications: function (jobId) {
    return request('/api/application/job/' + jobId)
  },
  // 14. 站内信
  notifications: function () {
    return request('/api/notification/mine')
  },
  // 15. AI 对话
  aiChat: function (message) {
    return request('/api/ai/chat', { method: 'POST', body: { message: message } })
  },
  // 16. 清空 AI 会话记忆
  aiClearMemory: function () {
    return request('/api/ai/memory', { method: 'DELETE' })
  }
}
