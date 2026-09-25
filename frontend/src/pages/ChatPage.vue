<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { HttpError, postJsonWithCsrf, request } from '../api/http'

interface Account { id: string; name: string; positioning: string; columns: string[]; weeklyTarget: number }
interface Summary { accountId: string; summary: string }
interface ResearchSource { materialId: string; title: string; snippet: string; sourceUrl: string | null; fileName: string | null; kind: string }
interface Research { status: 'MATCHED' | 'INSUFFICIENT_MATERIAL'; answer: string | null; sources: ResearchSource[]; webSearchStatus?: string | null }

const accounts = ref<Account[]>([])
const selectedAccount = ref('')
const summary = ref('')
const query = ref('')
const research = ref<Research | null>(null)
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

async function askFromMaterials() {
  if (!selectedAccount.value || !query.value.trim() || pending.value) return
  pending.value = true
  error.value = ''
  research.value = null
  try {
    const result = await postJsonWithCsrf('/api/agent/research', {
      accountId: selectedAccount.value, query: query.value.trim(),
    })
    if (!validResearch(result)) throw new Error('Invalid research response')
    research.value = result
  } catch (cause) { error.value = messageFor(cause) }
  finally { pending.value = false }
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

function validResearch(value: unknown): value is Research {
  return typeof value === 'object' && value !== null && 'status' in value && 'answer' in value && 'sources' in value
    && (value.status === 'MATCHED' || value.status === 'INSUFFICIENT_MATERIAL')
    && (value.answer === null || typeof value.answer === 'string') && Array.isArray(value.sources)
    && value.sources.every(source => typeof source === 'object' && source !== null
      && typeof source.materialId === 'string' && typeof source.title === 'string'
      && typeof source.snippet === 'string' && (source.sourceUrl === null || typeof source.sourceUrl === 'string'))
}

function messageFor(cause: unknown) {
  if (!(cause instanceof HttpError)) return '暂时无法完成请求，请稍后重试。'
  return ({ MODEL_NOT_CONFIGURED: '模型尚未配置，暂时不能生成内容。', MODEL_TIMEOUT: '模型响应超时，请稍后再试。',
    MODEL_UPSTREAM_FAILED: '模型服务暂时不可用，请稍后再试。', MODEL_AUTH_FAILED: '模型密钥验证失败。',
    MCP_NOT_CONFIGURED: '联网搜索尚未配置；本地资料没有匹配结果。',
    MCP_SEARCH_TOOL_UNAVAILABLE: '联网搜索服务没有提供预期的搜索工具。',
    MCP_UNAVAILABLE: '联网搜索服务暂时不可用，请稍后重试。',
    MCP_INVALID_RESULT: '联网搜索没有返回可引用的来源。',
    ACCOUNT_NOT_FOUND: '该账号已不可用，请刷新账号列表。', INVALID_QUERY: '请输入 100 字以内的问题。' }[cause.code ?? ''])
    ?? '暂时无法完成请求，请稍后重试。'
}

onMounted(loadAccounts)
</script>

<template>
  <section class="chat-card" aria-labelledby="chat-title" :aria-busy="pending">
    <div>
      <span class="section-index">02 / 创作助手</span>
      <h2 id="chat-title">从账号定位开始准备</h2>
      <p>先读取账号定位，或用当前账号可用的资料回答问题，并展示依据。本地资料无匹配时，问题会发送给已配置的联网搜索服务。</p>
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
        <label for="agent-query">向资料提问</label>
        <textarea id="agent-query" v-model="query" maxlength="100" rows="3" placeholder="例如：volatile 为什么能保证可见性？" :disabled="pending" />
        <button type="button" :disabled="pending || !query.trim()" @click="askFromMaterials">
          {{ pending ? '正在检索…' : '查资料并回答' }} <span aria-hidden="true">↗</span>
        </button>
      </template>
      <button v-else-if="!pending" type="button" @click="loadAccounts">重新读取账号 <span aria-hidden="true">↗</span></button>
      <p v-if="summary" class="agent-answer" role="status">{{ summary }}</p>
      <div v-if="research" class="research-answer" role="status">
        <p v-if="research.status === 'INSUFFICIENT_MATERIAL'">{{ research.webSearchStatus === 'MCP_NOT_CONFIGURED'
          ? '当前账号资料不足；联网搜索尚未配置。'
          : research.webSearchStatus ? '当前账号资料不足；联网搜索暂时不可用。'
          : '本地资料和联网搜索均未找到足够依据。' }}</p>
        <p v-else>{{ research.answer }}</p>
        <ol v-if="research.sources.length" class="source-list">
          <li v-for="source in research.sources" :key="source.materialId">
            <small v-if="source.kind === 'WEB'">网页来源 · 未经核实</small>
            <strong>{{ source.title }}</strong>
            <small>{{ source.snippet }}</small>
            <a v-if="source.sourceUrl" :href="source.sourceUrl" target="_blank" rel="noopener noreferrer">查看来源 ↗</a>
            <small v-else-if="source.fileName">来源文件：{{ source.fileName }}</small>
          </li>
        </ol>
      </div>
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
select, textarea { width: 100%; border: 1px solid #b7c1b4; border-radius: 4px; background: #faf9f5; color: #263b32; padding: 11px 12px; font: inherit; }
textarea { resize: vertical; }
select:focus-visible { outline: 2px solid #718540; outline-offset: 2px; }
.account-detail { font-size: 12px; }
button { cursor: pointer; border: 0; border-radius: 4px; padding: 12px 18px; background: #234d3b; color: #fff; margin-top: 9px; text-align: left; }
button span { float: right; }
button:disabled { cursor: wait; opacity: .6; }
.agent-answer { border-top: 1px solid #d5dcd0; margin-top: 8px; padding-top: 13px; color: #263b32; }
.research-answer { border-top: 1px solid #d5dcd0; margin-top: 8px; padding-top: 13px; }
.research-answer p { color: #263b32; }
.source-list { padding-left: 20px; font-size: 12px; }
.source-list li { margin: 12px 0; }
.source-list strong, .source-list small, .source-list a { display: block; }
.source-list small { color: #637166; overflow-wrap: anywhere; }
.source-list a { color: #234d3b; text-decoration: underline; }
.chat-error { color: #a13d2d; }
@media (max-width: 760px) { .chat-card { grid-template-columns: 1fr; gap: 16px; padding: 24px; } }
</style>
