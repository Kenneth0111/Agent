<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { HttpError, postJsonWithCsrf, request } from '../api/http'

interface Account { id: string; name: string; positioning: string; columns: string[]; weeklyTarget: number }
interface Summary { accountId: string; summary: string }

const accounts = ref<Account[]>([])
const selectedAccount = ref('')
const summary = ref('')
const error = ref('')
const pending = ref(true)
const selected = computed(() => accounts.value.find(account => account.id === selectedAccount.value))

async function loadAccounts() {
  pending.value = true
  error.value = ''
  try {
    const result = await request('/api/agent/accounts')
    if (!Array.isArray(result) || !result.every(validAccount)) throw new Error('Invalid account response')
    accounts.value = result
    selectedAccount.value = result[0]?.id ?? ''
  } catch (cause) {
    error.value = cause instanceof HttpError && cause.status === 401
      ? '登录后才能查看你的创作账号。' : '暂时无法读取账号，请稍后重试。'
  } finally { pending.value = false }
}

async function generateSummary() {
  if (!selectedAccount.value || pending.value) return
  pending.value = true
  error.value = ''
  summary.value = ''
  try {
    const result = await postJsonWithCsrf('/api/agent/account-summaries', { accountId: selectedAccount.value })
    if (!validSummary(result) || result.accountId !== selectedAccount.value) throw new Error('Invalid summary response')
    summary.value = result.summary
  } catch (cause) {
    error.value = messageFor(cause)
  } finally { pending.value = false }
}

function validAccount(value: unknown): value is Account {
  return typeof value === 'object' && value !== null && 'id' in value && 'name' in value
    && 'positioning' in value && 'columns' in value && 'weeklyTarget' in value
    && typeof value.id === 'string' && typeof value.name === 'string'
    && typeof value.positioning === 'string' && Array.isArray(value.columns)
    && typeof value.weeklyTarget === 'number'
}

function validSummary(value: unknown): value is Summary {
  return typeof value === 'object' && value !== null && 'accountId' in value && 'summary' in value
    && typeof value.accountId === 'string' && typeof value.summary === 'string' && value.summary.trim() !== ''
}

function messageFor(cause: unknown) {
  if (!(cause instanceof HttpError)) return '暂时无法生成摘要，请稍后重试。'
  return ({ MODEL_NOT_CONFIGURED: '模型尚未配置，暂时不能生成内容。', MODEL_TIMEOUT: '模型响应超时，请稍后再试。',
    MODEL_UPSTREAM_FAILED: '模型服务暂时不可用，请稍后再试。', ACCOUNT_NOT_FOUND: '该账号已不可用，请刷新账号列表。' }[cause.code ?? ''])
    ?? '暂时无法生成摘要，请稍后重试。'
}

onMounted(loadAccounts)
</script>

<template>
  <section class="chat-card" aria-labelledby="chat-title" :aria-busy="pending">
    <div>
      <span class="section-index">02 / 创作助手</span>
      <h2 id="chat-title">从账号定位开始准备</h2>
      <p>助手会先读取当前账号的内容定位，再生成一条简短的创作摘要。</p>
    </div>
    <div class="chat-controls">
      <template v-if="accounts.length">
        <label for="agent-account">创作账号</label>
        <select id="agent-account" v-model="selectedAccount" :disabled="pending">
          <option v-for="account in accounts" :key="account.id" :value="account.id">{{ account.name }}</option>
        </select>
        <p v-if="selected" class="account-detail">{{ selected.positioning }} · 每周 {{ selected.weeklyTarget }} 条</p>
        <button type="button" :disabled="pending" @click="generateSummary">
          {{ pending ? '正在准备…' : '生成账号摘要' }} <span aria-hidden="true">↗</span>
        </button>
      </template>
      <button v-else-if="!pending" type="button" @click="loadAccounts">重新读取账号 <span aria-hidden="true">↗</span></button>
      <p v-if="summary" class="agent-answer" role="status">{{ summary }}</p>
      <p v-if="error" class="chat-error" role="alert">{{ error }}</p>
    </div>
  </section>
</template>

<style scoped>
.chat-card { display: grid; grid-template-columns: 1fr 320px; gap: 40px; margin: 36px 0; border: 1px solid #d5dcd0; border-radius: 4px; background: #fbfaf6; padding: 32px 34px; }
h2 { font-size: 21px; font-weight: 500; margin: 18px 0 12px; }
p { color: #637166; font-size: 13px; line-height: 1.8; margin: 0; }
.chat-controls { display: grid; align-content: start; gap: 9px; }
label { font-size: 12px; color: #526252; }
select { width: 100%; border: 1px solid #b7c1b4; border-radius: 4px; background: #faf9f5; color: #263b32; padding: 11px 12px; font: inherit; }
select:focus-visible { outline: 2px solid #718540; outline-offset: 2px; }
.account-detail { font-size: 12px; }
button { cursor: pointer; border: 0; border-radius: 4px; padding: 12px 18px; background: #234d3b; color: #fff; margin-top: 9px; text-align: left; }
button span { float: right; }
button:disabled { cursor: wait; opacity: .6; }
.agent-answer { border-top: 1px solid #d5dcd0; margin-top: 8px; padding-top: 13px; color: #263b32; }
.chat-error { color: #a13d2d; }
@media (max-width: 760px) { .chat-card { grid-template-columns: 1fr; gap: 16px; padding: 24px; } }
</style>
