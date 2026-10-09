<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { HttpError, postJsonWithCsrf, putJsonWithCsrf, request } from '../api/http'

interface Account { id: string; name: string }
interface Material { id: string; title: string; accountIds: string[] }
interface Slot { column: string; materialIds: string[]; instruction: string }
interface Job { id: string; accountId: string; dayOfWeek: number; localTime: string; timeZone: string;
  enabled: boolean; instruction: string; slots: Slot[] }
interface Trigger { id: string; triggerSource: string; status: string; generationRunId: string | null;
  errorCode: string | null }
interface Summary { calls: number; succeeded: number; failed: number; pendingReconciliation: number;
  knownCostCny: number | null }
interface Budget { enabled: boolean; utcMonth: string; userCalls: number; userCallLimit: number;
  systemLimitCny: number; pauseCode: string | null }
interface UsageCall { id: string; taskId: string; kind: string; provider: string; status: string;
  costCny: number | null; reservedCny: number | null; errorCode: string | null; createdAt: string }

const accounts = ref<Account[]>([])
const materials = ref<Material[]>([])
const jobs = ref<Record<string, Job | null>>({})
const accountId = ref('')
const draft = ref<JobInput>(emptyDraft())
const savedDraft = ref(JSON.stringify(draft.value))
const triggers = ref<Trigger[]>([])
const summary = ref<Summary | null>(null)
const budget = ref<Budget | null>(null)
const calls = ref<UsageCall[]>([])
const pending = ref(false)
const triggering = ref(false)
const error = ref('')
const notice = ref('')
let selection = 0

type JobInput = Omit<Job, 'id' | 'accountId'>
const selectedJob = computed(() => jobs.value[accountId.value] ?? null)
const dirty = computed(() => JSON.stringify(draft.value) !== savedDraft.value)
const availableMaterials = computed(() => materials.value.filter(material =>
  material.accountIds.length === 0 || material.accountIds.includes(accountId.value)))

function emptyDraft(): JobInput {
  return { dayOfWeek: 1, localTime: '09:00', timeZone: 'Asia/Shanghai', enabled: false,
    instruction: '', slots: [
      { column: 'Java 面试', materialIds: [], instruction: '' },
      { column: 'Java 面试', materialIds: [], instruction: '' },
      { column: '英语跟读', materialIds: [], instruction: '' },
    ] }
}
function copyJob(job: Job | null): JobInput {
  return job ? { dayOfWeek: job.dayOfWeek, localTime: job.localTime.slice(0, 5),
    timeZone: job.timeZone, enabled: job.enabled, instruction: job.instruction,
    slots: job.slots.map(slot => ({ ...slot, materialIds: [...slot.materialIds] })) } : emptyDraft()
}
function validAccount(value: unknown): value is Account {
  return typeof value === 'object' && value !== null && 'id' in value && 'name' in value
    && typeof value.id === 'string' && typeof value.name === 'string'
}
function validMaterial(value: unknown): value is Material {
  return typeof value === 'object' && value !== null && 'id' in value && 'title' in value
    && 'accountIds' in value && typeof value.id === 'string' && typeof value.title === 'string'
    && Array.isArray(value.accountIds)
}
function validJob(value: unknown): value is Job {
  return typeof value === 'object' && value !== null && 'id' in value && 'accountId' in value
    && 'slots' in value && 'enabled' in value && typeof value.id === 'string'
    && typeof value.accountId === 'string' && Array.isArray(value.slots)
    && typeof value.enabled === 'boolean'
}
function apiError(cause: unknown): string {
  if (cause instanceof HttpError) return cause.code ? reason(cause.code) : `HTTP ${cause.status}`
  return cause instanceof Error && cause.name === 'AbortError' ? '请求超时，可刷新查看实际运行结果' : '请求失败，请稍后重试'
}
function money(value: number | null): string {
  return value === null ? '待核对' : `¥${Number(value).toFixed(6)}`
}
function reason(code: string): string {
  const labels: Record<string, string> = {
    MODEL_TIMEOUT: '模型响应超时', MODEL_AUTH_FAILED: '模型密钥无效',
    USER_CALL_LIMIT_REACHED: '本月用户调用次数已达上限',
    BUDGET_LIMIT_REACHED: '系统月预算余额不足',
    BUDGET_PRICE_UNCONFIGURED: '预算单价尚未配置',
    BUDGET_RECONCILIATION_REQUIRED: '存在待核对费用，已暂停新调用',
    MCP_UNAVAILABLE: '搜索服务暂不可用',
  }
  return labels[code] ? `${labels[code]}（${code}）` : code
}

async function load() {
  pending.value = true
  error.value = ''
  try {
    const [accountResult, materialResult, summaryResult, budgetResult, callsResult] = await Promise.all([
      request('/api/accounts'), request('/api/materials'), request('/api/usage/summary'),
      request('/api/usage/budget'), request('/api/usage/calls'),
    ])
    if (!Array.isArray(accountResult) || !accountResult.every(validAccount)
      || !Array.isArray(materialResult) || !materialResult.every(validMaterial)
      || !Array.isArray(callsResult) || typeof summaryResult !== 'object' || summaryResult === null
      || typeof budgetResult !== 'object' || budgetResult === null) throw new Error('Invalid response')
    const entries = await Promise.all(accountResult.map(async account => {
      try {
        const result = await request(`/api/generation-jobs/accounts/${encodeURIComponent(account.id)}`)
        if (!validJob(result)) throw new Error('Invalid job')
        return [account.id, result] as const
      } catch (cause) {
        if (cause instanceof HttpError && cause.status === 404) return [account.id, null] as const
        throw cause
      }
    }))
    accounts.value = accountResult
    materials.value = materialResult
    jobs.value = Object.fromEntries(entries)
    summary.value = summaryResult as Summary
    budget.value = budgetResult as Budget
    calls.value = callsResult as UsageCall[]
    if (!accountResult.some(account => account.id === accountId.value)) accountId.value = accountResult[0]?.id ?? ''
  } catch (cause) {
    error.value = apiError(cause)
  } finally {
    pending.value = false
  }
}

watch([accountId, jobs], async ([id]) => {
  const selected = ++selection
  draft.value = copyJob(jobs.value[id] ?? null)
  savedDraft.value = JSON.stringify(draft.value)
  triggers.value = []
  if (!id || !jobs.value[id]) return
  try {
    const result = await request(`/api/generation-jobs/${encodeURIComponent(jobs.value[id]!.id)}/triggers`)
    if (selected === selection && Array.isArray(result)) triggers.value = result as Trigger[]
  } catch (cause) {
    if (selected === selection) error.value = apiError(cause)
  }
})

async function save() {
  if (!accountId.value || !draft.value.instruction.trim()
    || draft.value.slots.some(slot => slot.materialIds.length < 1 || slot.materialIds.length > 3)) {
    error.value = '请填写生成要求，并为每条内容选择 1 至 3 份参考资料'
    return
  }
  pending.value = true
  error.value = ''
  notice.value = ''
  try {
    const result = await putJsonWithCsrf(`/api/generation-jobs/accounts/${encodeURIComponent(accountId.value)}`, draft.value)
    if (!validJob(result)) throw new Error('Invalid job')
    jobs.value = { ...jobs.value, [accountId.value]: result }
    draft.value = copyJob(result)
    savedDraft.value = JSON.stringify(draft.value)
    notice.value = '任务配置已保存'
  } catch (cause) { error.value = apiError(cause) }
  finally { pending.value = false }
}

async function toggle() {
  const job = selectedJob.value
  if (!job || dirty.value) return
  draft.value.enabled = !job.enabled
  await save()
}

async function trigger() {
  const job = selectedJob.value
  if (!job || dirty.value || triggering.value) return
  triggering.value = true
  error.value = ''
  notice.value = ''
  try {
    const result = await postJsonWithCsrf(`/api/generation-jobs/${encodeURIComponent(job.id)}/trigger`, {}, 180_000)
    if (typeof result !== 'object' || result === null || !('status' in result)) throw new Error('Invalid trigger')
    notice.value = `手动运行：${String(result.status)}`
  } catch (cause) { error.value = apiError(cause) }
  finally {
    triggering.value = false
    const result = await request(`/api/generation-jobs/${encodeURIComponent(job.id)}/triggers`).catch(() => null)
    if (Array.isArray(result)) triggers.value = result as Trigger[]
    await loadUsage()
  }
}

async function loadUsage() {
  try {
    const [nextSummary, nextBudget, nextCalls] = await Promise.all([
      request('/api/usage/summary'), request('/api/usage/budget'), request('/api/usage/calls'),
    ])
    summary.value = nextSummary as Summary
    budget.value = nextBudget as Budget
    if (Array.isArray(nextCalls)) calls.value = nextCalls as UsageCall[]
  } catch (cause) { error.value = apiError(cause) }
}

onMounted(load)
</script>

<template>
  <section class="operations" aria-labelledby="operations-title">
    <div class="section-heading">
      <div><span class="section-index">06 / 运行与用量</span><h2 id="operations-title">让创作任务，有迹可循。</h2>
        <p>查看每个账号的周生成任务、运行结果和外部调用用量。</p></div>
      <button type="button" class="quiet" :disabled="pending || triggering || dirty" @click="load">刷新记录</button>
    </div>
    <p v-if="error" class="message error" role="alert">{{ error }}</p>
    <p v-if="notice" class="message" role="status">{{ notice }}</p>

    <div class="overview">
      <article><span>本月调用</span><strong>{{ budget?.userCalls ?? '—' }}<small v-if="budget"> / {{ budget.userCallLimit }}</small></strong>
        <p>{{ budget?.enabled ? `额度控制已启用 · ${budget.utcMonth} UTC` : '额度控制未启用' }}</p></article>
      <article><span>已知估算费用</span><strong>{{ summary?.calls ? money(summary.knownCostCny) : '暂无' }}</strong>
        <p>待核对 {{ summary?.pendingReconciliation ?? '—' }} 次 · 失败 {{ summary?.failed ?? '—' }} 次</p></article>
      <article><span>预算状态</span><strong class="budget-code">{{ budget?.pauseCode ? reason(budget.pauseCode) : budget?.enabled ? '可用' : '未启用' }}</strong>
        <p>全系统月度目标 {{ budget ? money(budget.systemLimitCny) : '—' }}；费用以账单为准</p></article>
    </div>

    <div class="job-layout">
      <aside class="account-list" aria-label="账号任务列表">
        <h3>账号任务</h3>
        <p v-if="!accounts.length">暂无账号。请先在账号配置中创建。</p>
        <button v-for="account in accounts" :key="account.id" type="button" :class="{ active: accountId === account.id }"
          :disabled="pending || triggering || (dirty && accountId !== account.id)" @click="accountId = account.id">
          <span>{{ account.name }}</span><small>{{ jobs[account.id] ? (jobs[account.id]?.enabled ? '已启用' : '已暂停') : '未配置' }}</small>
        </button>
      </aside>

      <div v-if="accountId" class="job-detail">
        <div class="detail-head"><div><span class="section-index">周生成任务</span><h3>{{ accounts.find(account => account.id === accountId)?.name }}</h3></div>
          <span class="state-tag">{{ selectedJob ? (selectedJob.enabled ? '已启用' : '已暂停') : '尚未保存' }}</span></div>
        <div class="fields">
          <label>星期<select v-model.number="draft.dayOfWeek" :disabled="pending || triggering">
            <option v-for="(day, index) in ['周一', '周二', '周三', '周四', '周五', '周六', '周日']" :key="day" :value="index + 1">{{ day }}</option>
          </select></label>
          <label>当地时间<input v-model="draft.localTime" type="time" :disabled="pending || triggering" /></label>
          <label>时区<input v-model="draft.timeZone" :disabled="pending || triggering" maxlength="64" /></label>
        </div>
        <label class="wide-field">整周要求<textarea v-model="draft.instruction" rows="2" maxlength="500" :disabled="pending || triggering"
          placeholder="例如：每条围绕一个常见面试问题，语言简洁" /></label>
        <div class="slot-list"><div v-for="(slot, index) in draft.slots" :key="index" class="slot">
          <strong>{{ index + 1 }} · {{ slot.column }}</strong>
          <label>本条补充要求<input v-model="slot.instruction" maxlength="500" :disabled="pending || triggering" /></label>
          <fieldset><legend>参考资料 · 选 1 至 3 份</legend>
            <p v-if="!availableMaterials.length">这个账号暂无可用资料。</p>
            <label v-for="material in availableMaterials" :key="material.id" class="material-choice">
              <input v-model="slot.materialIds" type="checkbox" :value="material.id" :disabled="pending || triggering
                || (!slot.materialIds.includes(material.id) && slot.materialIds.length >= 3)" />{{ material.title }}
            </label>
          </fieldset>
        </div></div>
        <div class="actions">
          <button type="button" data-action="save-job" :disabled="pending || triggering || (!!selectedJob && !dirty)" @click="save">保存配置</button>
          <button v-if="selectedJob" type="button" data-action="toggle-job" class="quiet" :disabled="pending || triggering || dirty" @click="toggle">
            {{ selectedJob.enabled ? '暂停任务' : '启用任务' }}</button>
          <button v-if="dirty" type="button" data-action="discard-job" class="quiet" :disabled="pending || triggering"
            @click="draft = copyJob(selectedJob); savedDraft = JSON.stringify(draft)">放弃未保存修改</button>
        </div>
        <div class="run-box"><div><h4>最近运行</h4><p>手动触发会调用付费模型；自动扫描还需服务器显式开启。</p></div>
          <button type="button" data-action="trigger-job" :disabled="!selectedJob || dirty || pending || triggering || !!budget?.pauseCode" @click="trigger">
            {{ triggering ? '运行中…' : '手动运行一次' }}</button></div>
        <ul v-if="triggers.length" class="run-list"><li v-for="item in triggers" :key="item.id">
          <span>{{ item.triggerSource === 'SCHEDULED' ? '定时' : '手动' }} · {{ item.status }}</span>
          <code>{{ item.errorCode ? reason(item.errorCode) : item.generationRunId ?? item.id }}</code></li></ul>
        <p v-else class="empty">暂无运行记录。</p>
      </div>
    </div>

    <div class="ledger"><div class="detail-head"><div><span class="section-index">外部调用</span><h3>最近 100 条用量</h3></div>
      <button type="button" class="quiet" :disabled="pending || triggering" @click="loadUsage">刷新用量</button></div>
      <p v-if="!calls.length" class="empty">暂无模型或 MCP 搜索调用。</p>
      <div v-else class="ledger-scroll"><table><thead><tr><th>时间</th><th>来源</th><th>状态</th><th>估算费用</th><th>任务</th></tr></thead>
        <tbody><tr v-for="call in calls" :key="call.id"><td>{{ new Date(call.createdAt).toLocaleString('zh-CN') }}</td>
          <td>{{ call.provider }} · {{ call.kind }}</td><td>{{ call.errorCode ? reason(call.errorCode) : call.status }}</td>
          <td>{{ call.costCny !== null ? money(call.costCny) : call.reservedCny !== null ? `预留 ${money(call.reservedCny)}` : '待核对' }}</td>
          <td><code>{{ call.taskId }}</code></td></tr></tbody></table></div>
    </div>
  </section>
</template>

<style scoped>
.operations { margin: 44px 0; color: #263b32; }
.section-heading,.detail-head,.run-box { display: flex; justify-content: space-between; align-items: start; gap: 20px; }
.section-heading { border-top: 2px solid #315f46; padding: 25px 0 21px; }
.section-index { font: 11px Consolas, monospace; letter-spacing: 1.5px; color: #718540; }
h2 { font: 400 27px 'STSong','SimSun',serif; margin: 11px 0; } h3 { font-size: 18px; font-weight: 500; margin: 10px 0; }
h4 { font-size: 14px; margin: 0 0 5px; } p { font-size: 12px; line-height: 1.7; color: #667466; margin: 0; }
.overview { display: grid; grid-template-columns: repeat(3,minmax(0,1fr)); gap: 12px; margin-bottom: 20px; }
.overview article,.job-detail,.account-list,.ledger { border: 1px solid #d5dcd0; background: #fbfaf6; border-radius: 4px; }
.overview article { padding: 19px 20px; min-width: 0; } .overview span { display: block; color: #647465; font-size: 12px; }
.overview strong { display: block; font: 400 25px Georgia,serif; margin: 13px 0 7px; overflow-wrap: anywhere; }
.overview small { font-size: 15px; color: #768571; }.overview .budget-code { font: 600 16px Consolas,monospace; }
.job-layout { display: grid; grid-template-columns: 220px minmax(0,1fr); gap: 14px; }
.account-list { padding: 19px; align-self: start; display: grid; gap: 8px; }.account-list h3 { margin: 0 0 10px; }
.account-list button { display: flex; justify-content: space-between; gap: 6px; align-items: center; text-align: left; border: 1px solid transparent; background: transparent; color: #263b32; padding: 10px; cursor: pointer; }
.account-list button.active { background: #e8eddf; border-color: #b7c8aa; }.account-list small { color: #728271; white-space: nowrap; }
.job-detail { padding: 23px 25px; }.state-tag { background: #e8eddf; color: #315f46; padding: 5px 9px; font-size: 11px; white-space: nowrap; }
.fields { display: grid; grid-template-columns: repeat(3,minmax(0,1fr)); gap: 12px; margin: 18px 0; }
label { display: grid; gap: 6px; font-size: 12px; color: #526252; } input,select,textarea { width: 100%; min-width: 0; border: 1px solid #b7c1b4; border-radius: 4px; padding: 9px; background: #fff; color: #263b32; font: inherit; }
textarea { resize: vertical; }.slot-list { display: grid; grid-template-columns: repeat(3,minmax(0,1fr)); gap: 10px; margin: 18px 0; }.slot { border: 1px solid #d5dcd0; padding: 13px; min-width: 0; display: grid; align-content: start; gap: 11px; }.slot strong { font-size: 12px; }
fieldset { border: 0; padding: 0; margin: 0; } legend { font-size: 11px; color: #627064; margin-bottom: 7px; }.material-choice { display: flex; align-items: center; gap: 7px; margin: 7px 0; overflow-wrap: anywhere; }.material-choice input { width: auto; }
.actions { display: flex; flex-wrap: wrap; gap: 8px; margin: 16px 0 22px; } button { border: 1px solid #315f46; border-radius: 4px; padding: 8px 11px; background: #315f46; color: #fff; cursor: pointer; font: inherit; font-size: 12px; } button.quiet { background: transparent; color: #315f46; } button:disabled { opacity: .5; cursor: default; }
.run-box { border-top: 1px dashed #b7c1b4; padding: 16px 0; }.run-list { padding: 0; margin: 0; list-style: none; }.run-list li { display: flex; justify-content: space-between; gap: 10px; border-top: 1px solid #e1e5dc; padding: 10px 0; font-size: 12px; }.run-list code,.ledger code { overflow-wrap: anywhere; font-size: 11px; }
.ledger { padding: 23px 25px; margin-top: 14px; }.ledger-scroll { overflow-x: auto; } table { width: 100%; border-collapse: collapse; text-align: left; font-size: 12px; } th,td { padding: 11px 8px; border-top: 1px solid #e1e5dc; vertical-align: top; } th { color: #69796a; font-weight: 500; } .empty { padding: 12px 0; }
.message { margin: 0 0 12px; color: #315f46; }.error { color: #9c4c32; }
@media (max-width: 850px) { .job-layout { grid-template-columns: 1fr; }.account-list { display: flex; flex-wrap: wrap; align-items: center; }.slot-list { grid-template-columns: 1fr; } }
@media (max-width: 700px) { .overview,.fields { grid-template-columns: 1fr; }.section-heading { flex-direction: column; }.job-detail,.ledger { padding: 19px; }.run-box { flex-direction: column; } }
</style>
