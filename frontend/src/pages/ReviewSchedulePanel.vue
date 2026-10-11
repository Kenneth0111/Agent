<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { HttpError, putJsonWithCsrf, request } from '../api/http'
import { object, statusLabel, validRequest, type ReviewRequest } from './operation-review'
const props = defineProps<{ accountId: string }>()
const emit = defineEmits<{ report: [id: string] }>()
interface Input { dayOfWeek: number; localTime: string; timeZone: string; enabled: boolean; topic: string }
interface Job extends Input { id: string; accountId: string }
interface Trigger { id: string; accountId: string; runKey: string; request: ReviewRequest; status: string;
  reportId: string | null; errorCode: string | null; createdAt: string }
interface View { schedulerEnabled: boolean; job: Job | null; triggers: Trigger[] }
function empty(): Input { return { dayOfWeek: 7, localTime: '09:00', timeZone: 'Asia/Shanghai', enabled: false, topic: '' } }
const draft = ref<Input>(empty())
const saved = ref(JSON.stringify(draft.value))
const schedulerEnabled = ref(false)
const triggers = ref<Trigger[]>([])
const pending = ref(false)
const loaded = ref(false)
const error = ref('')
const notice = ref('')
const dirty = computed(() => JSON.stringify(draft.value) !== saved.value)
const days = ['周一', '周二', '周三', '周四', '周五', '周六', '周日']
let revision = 0
function validJob(value: unknown): value is Job {
  return object(value) && typeof value.id === 'string' && value.accountId === props.accountId
    && Number.isInteger(value.dayOfWeek) && (value.dayOfWeek as number) >= 1 && (value.dayOfWeek as number) <= 7
    && typeof value.localTime === 'string' && /^([01]\d|2[0-3]):[0-5]\d(:00)?$/.test(value.localTime)
    && typeof value.timeZone === 'string' && typeof value.topic === 'string' && typeof value.enabled === 'boolean'
}
function validView(value: unknown): value is View {
  return object(value) && typeof value.schedulerEnabled === 'boolean' && (value.job === null || validJob(value.job))
    && Array.isArray(value.triggers) && value.triggers.length <= 20 && (value.job !== null || value.triggers.length === 0)
    && value.triggers.every(row => object(row) && row.accountId === props.accountId && typeof row.id === 'string'
      && typeof row.runKey === 'string' && typeof row.status === 'string' && statusLabel(row.status) !== row.status
      && (row.reportId === null || typeof row.reportId === 'string') && (row.errorCode === null || typeof row.errorCode === 'string')
      && typeof row.createdAt === 'string' && Number.isFinite(Date.parse(row.createdAt)) && validRequest(row.request, props.accountId))
}
function apply(value: View, replaceDraft: boolean) {
  schedulerEnabled.value = value.schedulerEnabled; triggers.value = value.triggers; loaded.value = true
  if (replaceDraft) {
    const job = value.job
    draft.value = job ? { dayOfWeek: job.dayOfWeek, localTime: job.localTime.slice(0, 5), timeZone: job.timeZone,
      enabled: job.enabled, topic: job.topic } : empty()
    saved.value = JSON.stringify(draft.value)
  }
}
function path() { return `/api/metrics/review-jobs/accounts/${encodeURIComponent(props.accountId)}` }
function failure(cause: unknown) {
  return cause instanceof HttpError && cause.status === 401 ? '登录已失效，请重新登录。'
    : cause instanceof HttpError && cause.code === 'INVALID_REVIEW_JOB' ? '请核对星期、整分钟时间、时区和 1～100 字关键词。'
      : '暂时无法读取或保存配置，请刷新确认服务器状态后重试。'
}
async function load() {
  if (pending.value || !props.accountId) return
  const current = ++revision
  pending.value = true; error.value = ''; notice.value = ''
  const replaceDraft = !loaded.value || !dirty.value
  try {
    const result = await request(path())
    if (current !== revision) return
    if (!validView(result)) throw new Error('Invalid review job')
    apply(result, replaceDraft)
    if (!replaceDraft) notice.value = '已刷新执行状态，保留尚未保存的配置。'
  } catch (cause) { if (current === revision) error.value = failure(cause) }
  finally { if (current === revision) pending.value = false }
}
async function save() {
  if (pending.value || !loaded.value || !props.accountId) return
  if (!draft.value.topic.trim() || draft.value.topic.trim().length > 100 || !draft.value.timeZone.trim()) {
    error.value = '请填写 1～100 字关键词和有效时区。'; return
  }
  const current = ++revision
  pending.value = true; error.value = ''; notice.value = ''
  const input = { ...draft.value, topic: draft.value.topic.trim(), timeZone: draft.value.timeZone.trim() }
  try {
    const result = await putJsonWithCsrf(path(), input)
    if (current !== revision) return
    if (!validView(result) || !result.job || result.job.dayOfWeek !== input.dayOfWeek
      || result.job.localTime.slice(0, 5) !== input.localTime || result.job.timeZone !== input.timeZone
      || result.job.enabled !== input.enabled || result.job.topic !== input.topic) throw new Error('Invalid saved review job')
    apply(result, true)
    notice.value = '设置已保存。保存不会立即生成报告；需账号启用且服务器扫描开启后按时执行。'
  } catch (cause) { if (current === revision) error.value = failure(cause) }
  finally { if (current === revision) pending.value = false }
}
function time(value: string) { return new Date(value).toLocaleString('zh-CN', { hour12: false }) }
watch(() => props.accountId, () => {
  revision++; draft.value = empty(); saved.value = JSON.stringify(draft.value); loaded.value = false
  schedulerEnabled.value = false; triggers.value = []; pending.value = false; error.value = ''; notice.value = ''
  void load()
}, { immediate: true })
onBeforeUnmount(() => { revision++ })
</script>

<template>
  <section class="review-schedule" aria-label="每周定时复盘" :aria-busy="pending">
    <div class="heading"><h3>03 / 每周定时复盘</h3><button type="button" :disabled="pending" @click="load">刷新定时状态</button></div>
    <p>按计划时刻之前的 7 个本地日复盘，同一账号任务每周最多一次。无数据跳过模型；有依据时按现有预算调用模型。编辑设置不会重跑本周已执行的任务。</p>
    <p v-if="loaded && !schedulerEnabled" class="warning">服务器定时复盘尚未开启。可以先保存配置，当前不会自动生成报告。</p>
    <form @submit.prevent="save"><fieldset :disabled="pending || !loaded">
      <label class="enable"><input v-model="draft.enabled" type="checkbox" aria-label="启用定时复盘">启用这个账号的每周复盘</label>
      <label>评论 / 资料检索关键词<input v-model="draft.topic" aria-label="定时复盘关键词" maxlength="100" placeholder="例如：线程池" required></label>
      <div class="fields"><label>星期<select v-model="draft.dayOfWeek" aria-label="定时复盘星期"><option v-for="(day, index) in days" :key="day" :value="index + 1">{{ day }}</option></select></label>
        <label>当地时间<input v-model="draft.localTime" type="time" aria-label="定时复盘时间" required></label>
        <label>时区<input v-model="draft.timeZone" aria-label="定时复盘时区" placeholder="Asia/Shanghai" maxlength="64" required></label></div>
    </fieldset><button type="submit" :disabled="pending || !loaded">保存定时复盘设置</button><span v-if="dirty" class="unsaved">有未保存修改</span></form>
    <p v-if="error" class="error" role="alert">{{ error }}</p><p v-if="notice" role="status">{{ notice }}</p><p v-if="pending" role="status">正在同步定时复盘设置…</p>
    <p v-if="loaded && !triggers.length">暂无定时复盘执行记录。</p>
    <div v-if="triggers.length" class="table-wrap" tabindex="0" aria-label="定时复盘记录，可横向滚动"><table><caption>最近 20 次周期记录 · 扫描失败或结果不明不会自动重发</caption>
      <thead><tr><th>当地周起始</th><th>检索关键词 / 观测窗口（本地显示）</th><th>状态</th><th>报告</th></tr></thead>
      <tbody><tr v-for="trigger in triggers" :key="trigger.id"><td>{{ trigger.runKey }}</td><td>{{ trigger.request.topic }}<small>{{ time(trigger.request.from) }} → {{ time(trigger.request.to) }}（结束不含）</small></td>
        <td>{{ statusLabel(trigger.status) }}<small v-if="trigger.errorCode">{{ trigger.errorCode }}</small></td>
        <td><button v-if="trigger.reportId" type="button" @click="emit('report', trigger.reportId)">查看已保存报告</button><span v-else>尚未保存报告</span></td></tr></tbody>
    </table></div>
    <p>服务器在计划当天到点后检查；若当天服务一直离线，不补跑旧日期。超时或中断先核对用量和已保存事实，下周新周期仍受预算控制。</p>
  </section>
</template>

<style scoped>
.review-schedule { margin-top: 28px; padding-top: 24px; border-top: 1px solid #d5dcd0; min-width: 0; }
.heading { display: flex; flex-wrap: wrap; justify-content: space-between; align-items: center; gap: 12px; }
h3 { font-size: 15px; color: #234d3b; font-weight: 500; } p, caption { font-size: 12px; color: #637166; line-height: 1.8; }
fieldset { border: 0; padding: 0; margin: 20px 0 16px; min-width: 0; } label { display: grid; gap: 9px; font-size: 12px; color: #526252; min-width: 0; }
input, select { box-sizing: border-box; width: 100%; padding: 10px; font: inherit; color: #263b32; background: #faf9f5; border: 1px solid #b7c1b4; border-radius: 4px; }
.enable { display: flex; align-items: center; margin-bottom: 18px; } .enable input { width: auto; accent-color: #234d3b; }
.fields { display: grid; grid-template-columns: 1fr 1fr 2fr; gap: 18px; margin-top: 18px; }
button { padding: 9px 14px; background: transparent; color: #234d3b; border: 1px solid #b7c1b4; border-radius: 4px; cursor: pointer; }
button:disabled { opacity: .45; cursor: default; } .unsaved { font-size: 12px; color: #746139; margin-left: 12px; }
.warning { padding: 12px 16px; background: #f1ecd9; } .error { color: #a13d2d; }
.table-wrap { overflow-x: auto; margin-top: 20px; } table { width: 100%; border-collapse: collapse; font-size: 12px; text-align: left; }
caption { text-align: left; margin-bottom: 10px; } th, td { padding: 12px 10px; border-bottom: 1px solid #d5dcd0; white-space: nowrap; } th { font-weight: 500; }
small { display: block; margin-top: 5px; color: #637166; } :focus-visible { outline: 2px solid #577c39; outline-offset: 3px; }
@media (max-width: 760px) { .fields { grid-template-columns: 1fr; } }
</style>
