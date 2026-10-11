<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { HttpError, postJsonWithCsrf, request } from '../api/http'
import OperationReport from './OperationReport.vue'
import { statusLabel, validReport, validRequest, type Report, type ReviewRequest } from './operation-review'
const props = defineProps<{ accountId: string }>()
const reports = ref<Report[]>([])
const selectedId = ref('')
const topic = ref('')
const from = ref(localInput(new Date(Date.now() - 7 * 86400000)))
const to = ref(localInput(new Date()))
const unresolved = ref<ReviewRequest | null>(null)
const loading = ref(false)
const generating = ref(false)
const error = ref('')
let selection = 0
let reads = 0
const selected = computed(() => reports.value.find(report => report.id === selectedId.value))
function localInput(date: Date) {
  const pad = (value: number) => String(value).padStart(2, '0')
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}T${pad(date.getHours())}:${pad(date.getMinutes())}`
}
function storageKey() { return `creator-review-request:${props.accountId}` }
function store(value: ReviewRequest | null) {
  try { if (value) sessionStorage.setItem(storageKey(), JSON.stringify(value)); else sessionStorage.removeItem(storageKey()) }
  catch {
    error.value = value ? '浏览器无法保存请求编号，暂未发送本次请求。请允许存储后再试。'
      : '报告已保存，但无法清除本地请求编号；重试仍会读取原报告。'
    return false
  }
  return true
}
async function load() {
  const current = selection
  const read = ++reads
  loading.value = true; error.value = ''
  try {
    const result = await request(`/api/metrics/reviews?accountId=${encodeURIComponent(props.accountId)}`)
    if (current !== selection || read !== reads) return
    if (!Array.isArray(result) || !result.every(report => validReport(report, props.accountId))) throw new Error('Invalid reports')
    reports.value = result
    if (!result.some(report => report.id === selectedId.value)) selectedId.value = result[0]?.id ?? ''
  } catch { if (current === selection && read === reads) error.value = '暂时无法读取报告，请重新登录或刷新重试。' }
  finally { if (current === selection && read === reads) loading.value = false }
}
async function generate() {
  if (generating.value || !props.accountId) return
  let input = unresolved.value
  if (!input) {
    const start = new Date(from.value).getTime(); const end = new Date(to.value).getTime()
    if (!topic.value.trim() || topic.value.trim().length > 100 || !Number.isFinite(start) || !Number.isFinite(end)
      || start >= end || end - start > 31 * 86400000) { error.value = '请填写 1～100 字关键词和最长 31 天的有效观测窗口。'; return }
    input = { accountId: props.accountId, topic: topic.value.trim(), from: new Date(start).toISOString(), to: new Date(end).toISOString(), requestId: crypto.randomUUID() }
    unresolved.value = input
  }
  if (!store(input)) return
  const current = selection
  generating.value = true; error.value = ''
  try {
    const result = await postJsonWithCsrf('/api/metrics/reviews', input, 120_000)
    if (current !== selection) return
    if (!validReport(result, props.accountId) || result.request.requestId !== input.requestId
      || result.request.topic !== input.topic || Date.parse(result.request.from) !== Date.parse(input.from)
      || Date.parse(result.request.to) !== Date.parse(input.to)) throw new Error('Invalid report')
    reads++; loading.value = false
    reports.value = [result, ...reports.value.filter(report => report.id !== result.id)].slice(0, 20)
    selectedId.value = result.id
    if (!['RUNNING', 'GENERATING'].includes(result.status)) { unresolved.value = null; store(null) }
  } catch (cause) {
    if (current !== selection) return
    error.value = cause instanceof HttpError ? `复盘请求未确认（${cause.code ?? 'HTTP ' + cause.status}）。请复用本次请求查询结果。`
      : '复盘请求结果尚未确认。请复用本次请求查询结果；不会为重试生成新编号。'
    if (cause instanceof HttpError && [400, 404, 409].includes(cause.status)) {
      unresolved.value = null; store(null)
      error.value = `请求被拒绝（${cause.code ?? 'HTTP ' + cause.status}），请核对参数后重新创建复盘。`
    }
  } finally { if (current === selection) generating.value = false }
}
watch(() => props.accountId, () => {
  selection++; reads++; reports.value = []; selectedId.value = ''; error.value = ''; generating.value = false; loading.value = false
  unresolved.value = null
  try {
    const saved: unknown = JSON.parse(sessionStorage.getItem(storageKey()) ?? 'null')
    if (validRequest(saved, props.accountId)) {
      unresolved.value = saved; topic.value = saved.topic; from.value = localInput(new Date(saved.from)); to.value = localInput(new Date(saved.to))
    } else { topic.value = '' }
  } catch { topic.value = '' }
  if (props.accountId) void load()
}, { immediate: true })
onBeforeUnmount(() => { selection++; reads++ })
</script>

<template>
  <section class="review-panel" aria-label="复盘与选题实验" :aria-busy="generating || loading">
    <div class="review-heading"><h3>02 / 复盘与选题实验</h3><button type="button" :disabled="loading || !accountId" @click="load">刷新已保存报告</button></div>
    <p>导入观测数据后，选择关键词与时间窗口。Agent 会保存事实和来源，再提出下一轮实验建议；有依据时会调用模型并计入用量。</p>
    <form @submit.prevent="generate"><fieldset :disabled="generating || !!unresolved">
      <label>评论 / 资料检索关键词<input v-model="topic" aria-label="复盘关键词" maxlength="100" placeholder="例如：线程池" required></label>
      <div class="window"><label>观测起始（本地时区）<input v-model="from" type="datetime-local" aria-label="复盘起始时间" required></label>
        <label>观测结束，不包含该时刻<input v-model="to" type="datetime-local" aria-label="复盘结束时间" required></label></div>
    </fieldset>
      <button type="submit" :disabled="generating || loading || !accountId">{{ generating ? '正在复盘…' : unresolved ? '复用本次请求查询结果' : '创建新的复盘' }}</button>
    </form>
    <p v-if="unresolved" class="warning">已保留本次请求编号 {{ unresolved.requestId }}。参数暂时锁定，避免误发第二次模型请求。</p>
    <p v-if="error" class="error" role="alert">{{ error }}</p><p v-if="loading" role="status">正在读取保存的报告…</p>
    <p v-else-if="!reports.length && !error">尚无复盘报告。新号没有指标或主题匹配评论时，会保存“样本不足”报告并跳过模型。</p>
    <label v-if="reports.length">历史复盘（最近 20 份）<select v-model="selectedId" aria-label="历史复盘">
      <option v-for="report in reports" :key="report.id" :value="report.id">{{ report.request.topic }} · {{ new Date(report.createdAt).toLocaleString('zh-CN') }} · {{ statusLabel(report.status) }}</option>
    </select></label>
    <OperationReport v-if="selected" :report="selected" />
  </section>
</template>

<style scoped>
.review-panel { margin-top: 28px; padding-top: 24px; border-top: 1px solid #d5dcd0; min-width: 0; }
.review-heading { display: flex; flex-wrap: wrap; gap: 12px; align-items: center; justify-content: space-between; }
h3 { font-size: 15px; font-weight: 500; color: #234d3b; } p { font-size: 12px; color: #637166; line-height: 1.8; }
form { margin: 20px 0; } fieldset { padding: 0; border: 0; margin: 0 0 16px; min-width: 0; }
label { display: grid; gap: 9px; color: #526252; font-size: 12px; min-width: 0; }
input, select { box-sizing: border-box; width: 100%; padding: 10px; font: inherit; color: #263b32; background: #faf9f5; border: 1px solid #b7c1b4; border-radius: 4px; }
.window { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 18px; margin-top: 18px; }
button { padding: 9px 14px; background: transparent; color: #234d3b; border: 1px solid #b7c1b4; border-radius: 4px; cursor: pointer; }
button[type=submit] { background: #234d3b; color: #fbfaf6; } button:disabled { opacity: .45; cursor: default; }
.error { color: #a13d2d; } .warning { background: #f1ecd9; padding: 12px 16px; overflow-wrap: anywhere; }
:focus-visible { outline: 2px solid #577c39; outline-offset: 3px; }
@media (max-width: 760px) { .window { grid-template-columns: 1fr; } }
</style>
