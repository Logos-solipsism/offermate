<template>
  <div class="container">
    <div class="page-head">
      <div>
        <h1 class="page-title">AI 助手</h1>
        <p class="page-sub">基于 Function Calling 的求职助手，可以查询投递进度、推荐职位、分析候选资料</p>
      </div>
      <button class="btn btn-outline btn-sm" @click="clearMemory" :disabled="clearing">
        {{ clearing ? '清空中…' : '清空记忆' }}
      </button>
    </div>

    <div class="card chat-card">
      <div class="quick-questions">
        <button class="btn btn-outline btn-sm" v-for="question in quickQuestions" :key="question" @click="useQuestion(question)">
          {{ question }}
        </button>
      </div>

      <div class="chat-body" ref="bodyRef">
        <div v-if="!messages.length" class="empty">问点什么吧，例如“我投的岗位到哪了”</div>

        <div class="bubble-row" :class="item.role" v-for="(item, index) in messages" :key="index">
          <div>
            <div class="bubble-role" v-if="item.role === 'ai'">OfferMate 助手</div>
            <div class="bubble-role" v-else style="text-align: right">我</div>
            <div class="bubble" :class="item.error ? 'error' : item.role">{{ item.text }}</div>
          </div>
        </div>

        <div class="bubble-row ai" v-if="sending">
          <div>
            <div class="bubble-role">OfferMate 助手</div>
            <div class="bubble ai">正在思考…</div>
          </div>
        </div>
      </div>

      <div class="chat-input-row">
        <input class="input" v-model.trim="draft" placeholder="输入问题后回车发送" @keyup.enter="send" :disabled="sending" />
        <button class="btn btn-primary" @click="send" :disabled="sending || !draft">发送</button>
      </div>
    </div>
  </div>
</template>

<script setup>
import { nextTick, ref } from 'vue'
import { api } from '../api.js'

const quickQuestions = [
  '我投的岗位到哪了',
  '帮我推荐北京的 Java 岗位',
  '我的简历还有什么问题'
]

const messages = ref([])
const draft = ref('')
const sending = ref(false)
const clearing = ref(false)
const bodyRef = ref(null)

function scrollToBottom() {
  nextTick(function () {
    if (bodyRef.value) {
      bodyRef.value.scrollTop = bodyRef.value.scrollHeight
    }
  })
}

function useQuestion(question) {
  draft.value = question
  send()
}

// 发送消息：按顺序追加用户气泡与助手气泡，限流时展示后端提示
async function send() {
  const text = draft.value
  if (!text || sending.value) {
    return
  }
  messages.value.push({ role: 'user', text: text })
  draft.value = ''
  sending.value = true
  scrollToBottom()
  try {
    const answer = await api.aiChat(text)
    messages.value.push({ role: 'ai', text: answer || '（助手没有返回内容）' })
  } catch (e) {
    messages.value.push({ role: 'ai', text: e.message, error: true })
  } finally {
    sending.value = false
    scrollToBottom()
  }
}

// 清空会话记忆
async function clearMemory() {
  clearing.value = true
  try {
    await api.aiClearMemory()
    messages.value = []
    messages.value.push({ role: 'ai', text: '会话记忆已清空，我们重新开始吧。' })
  } catch (e) {
    messages.value.push({ role: 'ai', text: e.message, error: true })
  } finally {
    clearing.value = false
    scrollToBottom()
  }
}
</script>
