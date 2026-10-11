<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { request } from '../api/http'

interface Account { id: string; name: string }
interface Work { id: string; videoId: string; sources: string[] }
interface Point {
  capturedAt: string; previousCapturedAt: string | null
  views: string | null; likes: string | null; commentCount: string | null
  viewsDelta: string | null; likesDelta: string | null; commentCountDelta: string | null
  likeViewPercent: string | null; commentViewPercent: string | null; correction: boolean
}
interface Trend { workId: string; source: string; truncated: boolean; points: Point[] }
const accounts = ref<Account[]>([])
const works = ref<Work[]>([])
const accountId = ref('')
const workId = ref('')
const source = ref('')
const trend = ref<Trend | null>(null)
const pending = ref(false)
const error = ref('')
const sources = computed(() => works.value.find(work => work.id === workId.value)?.sources ?? [])
const latest = computed(() => trend.value?.points.at(-1))
const metrics = [
  { key: 'views', delta: 'viewsDelta', label: '播放量' },
  { key: 'likes', delta: 'likesDelta', label: '点赞量' },
  { key: 'commentCount', delta: 'commentCountDelta', label: '评论量' },
] as const
let revision = 0

function object(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null
}
function validPoint(value: unknown): value is Point {
  if (!object(value) || typeof value.capturedAt !== 'string' || !Number.isFinite(Date.parse(value.capturedAt))
    || !(value.previousCapturedAt === null || typeof value.previousCapturedAt === 'string'
      && Number.isFinite(Date.parse(value.previousCapturedAt))) || typeof value.correction !== 'boolean') return false
  return ['views', 'likes', 'commentCount'].every(key => value[key] === null
    || typeof value[key] === 'string' && /^\d+$/.test(value[key]))
    && ['viewsDelta', 'likesDelta', 'commentCountDelta'].every(key => value[key] === null
      || typeof value[key] === 'string' && /^-?\d+$/.test(value[key]))
    && ['likeViewPercent', 'commentViewPercent'].every(key => value[key] === null
      || typeof value[key] === 'string' && /^\d+(\.\d+)?$/.test(value[key]))
}
function validTrend(value: unknown): value is Trend {
  return object(value) && value.workId === workId.value && value.source === source.value
    && typeof value.truncated === 'boolean' && Array.isArray(value.points) && value.points.every(validPoint)
}

async function loadTrend() {
  const current = ++revision
  trend.value = null
  error.value = ''
  pending.value = false
  if (!workId.value || !source.value) return
  pending.value = true
  try {
    const result = await request(`/api/metrics/works/${encodeURIComponent(workId.value)}/trend?source=${encodeURIComponent(source.value)}`)
    if (current !== revision) return
    if (!validTrend(result)) throw new Error('Invalid trend response')
    trend.value = result
  } catch { if (current === revision) error.value = '暂时无法读取观测记录，请重试。' }
  finally { if (current === revision) pending.value = false }
}

function changeWork() {
  source.value = sources.value[0] ?? ''
  void loadTrend()
}

async function loadWorks() {
  const current = ++revision
  works.value = []
  workId.value = ''
  source.value = ''
  trend.value = null
  error.value = ''
  pending.value = false
  if (!accountId.value) return
  pending.value = true
  try {
    const result = await request(`/api/metrics/works?accountId=${encodeURIComponent(accountId.value)}`)
    if (current !== revision) return
    if (!Array.isArray(result) || !result.every(value => object(value) && typeof value.id === 'string'
      && typeof value.videoId === 'string' && Array.isArray(value.sources)
      && value.sources.every(item => typeof item === 'string'))) throw new Error('Invalid works response')
    works.value = result
    workId.value = result[0]?.id ?? ''
    changeWork()
  } catch { if (current === revision) error.value = '暂时无法读取作品，请重试。' }
  finally { if (current === revision) pending.value = false }
}

async function loadAccounts() {
  const current = ++revision
  pending.value = true
  try {
    const result = await request('/api/accounts')
    if (current !== revision) return
    if (!Array.isArray(result) || !result.every(value => object(value)
      && typeof value.id === 'string' && typeof value.name === 'string')) throw new Error('Invalid accounts response')
    accounts.value = result
    accountId.value = result[0]?.id ?? ''
    await loadWorks()
  } catch { if (current === revision) { error.value = '暂时无法读取账号，请刷新页面重试。'; pending.value = false } }
}

function count(value: string | null | undefined) { return value == null ? '未知' : value }
function delta(point: Point, key: 'viewsDelta' | 'likesDelta' | 'commentCountDelta') {
  if (point.previousCapturedAt === null) return trend.value?.truncated ? '窗口首条，变化未知' : '首次观测'
  const value = point[key]
  return value === null ? '变化未知' : value === '0' || value.startsWith('-') ? value : `+${value}`
}
function percent(value: string | null) { return value === null ? '不可计算' : `${value}%` }
function time(value: string) { return new Date(value).toLocaleString('zh-CN', { hour12: false }) }
onMounted(loadAccounts)
onBeforeUnmount(() => { revision++ })
</script>

<template>
  <section class="analytics-card" aria-labelledby="metrics-title" :aria-busy="pending">
    <div class="heading">
      <div><span class="section-index">09 / 作品观测</span><h2 id="metrics-title">从反馈，认识下一条作品</h2></div>
      <button type="button" :disabled="pending || !accountId" @click="loadWorks">刷新记录</button>
    </div>
    <p class="explanation">累计指标来自导入的观测数据。变化量只比较同一作品、同一来源的相邻记录，不代表平台实时数据。</p>
    <div class="selectors">
      <label for="metrics-account">内容账号<select id="metrics-account" v-model="accountId" @change="loadWorks">
        <option v-for="account in accounts" :key="account.id" :value="account.id">{{ account.name }}</option>
      </select></label>
      <label for="metrics-work">作品 ID<select id="metrics-work" v-model="workId" :disabled="!works.length" @change="changeWork">
        <option v-for="work in works" :key="work.id" :value="work.id">{{ work.videoId }}</option>
      </select></label>
      <label for="metrics-source">数据来源<select id="metrics-source" v-model="source" :disabled="!sources.length" @change="loadTrend">
        <option v-for="item in sources" :key="item" :value="item">{{ item }}</option>
      </select></label>
    </div>
    <p v-if="error" class="error" role="alert">{{ error }}</p>
    <p v-else-if="pending" role="status">正在读取观测记录…</p>
    <p v-else-if="!accounts.length" class="empty">先创建内容账号，再记录作品数据。</p>
    <p v-else-if="!works.length" class="empty">这个账号还没有导入作品数据。发布作品后，可以从指标 CSV 开始积累记录。</p>
    <p v-else-if="!latest" class="empty">这个来源暂无指标快照。</p>
    <template v-if="latest && trend">
      <div class="metric-summary">
        <article v-for="metric in metrics" :key="metric.key">
          <span>{{ metric.label }} · 最近观测</span><strong>{{ count(latest[metric.key]) }}</strong>
          <small>{{ delta(latest, metric.delta) }}</small>
        </article>
      </div>
      <p class="observation-time">观测时间：<time :datetime="latest.capturedAt" :title="latest.capturedAt">{{ time(latest.capturedAt) }}</time>（本地时区）</p>
      <p v-if="trend.points.some(point => point.correction)" class="notice">部分累计值下降，可能涉及平台口径修正或导入数据差异；请核对来源，不能将其解释为新增量。</p>
      <p v-if="trend.truncated" class="notice">当前显示最近 1000 条观测；窗口首条不计算变化量。</p>
      <div class="observations" tabindex="0" aria-label="观测趋势记录，可横向滚动">
        <table>
          <caption>{{ source }} · 相邻观测趋势（时间从早到晚）</caption>
          <thead><tr><th scope="col">观测时间</th><th v-for="metric in metrics" :key="metric.key" scope="col">{{ metric.label }} / 变化</th><th scope="col">点赞 / 播放</th><th scope="col">评论 / 播放</th><th scope="col">提示</th></tr></thead>
          <tbody><tr v-for="point in trend.points" :key="point.capturedAt">
            <td><time :datetime="point.capturedAt" :title="point.capturedAt">{{ time(point.capturedAt) }}</time></td>
            <td v-for="metric in metrics" :key="metric.key">{{ count(point[metric.key]) }}<small>{{ delta(point, metric.delta) }}</small></td>
            <td>{{ percent(point.likeViewPercent) }}</td><td>{{ percent(point.commentViewPercent) }}</td>
            <td>{{ point.correction ? '累计值下降，需核对' : '—' }}</td>
          </tr></tbody>
        </table>
      </div>
      <p class="explanation">未知值保持为空；首次观测没有变化量。比值是累计点赞或评论除以累计播放，缺少数据或播放为 0 时不可计算。观测间隔不同，变化量不能直接用于比较增长速度。</p>
    </template>
  </section>
</template>

<style scoped>
.analytics-card { margin: 36px 0; padding: 32px 34px; border: 1px solid #d5dcd0; border-radius: 4px; background: #fbfaf6; min-width: 0; }
.heading { display: flex; justify-content: space-between; align-items: center; gap: 16px; }
h2 { font-size: 21px; font-weight: 500; margin: 18px 0 12px; }
p { font-size: 13px; color: #637166; line-height: 1.8; }
button { padding: 9px 14px; background: transparent; color: #234d3b; border: 1px solid #b7c1b4; border-radius: 4px; cursor: pointer; white-space: nowrap; }
.selectors { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 18px; margin: 22px 0; }
label { display: grid; gap: 9px; color: #526252; font-size: 12px; min-width: 0; }
select { width: 100%; padding: 10px; font: inherit; color: #263b32; background: #faf9f5; border: 1px solid #b7c1b4; border-radius: 4px; }
select:focus-visible, .observations:focus-visible { outline: 2px solid #577c39; outline-offset: 3px; }
.metric-summary { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 18px; }
.metric-summary article { border-top: 1px solid #d5dcd0; padding: 18px 0; min-width: 0; }
.metric-summary span, .metric-summary small { font-size: 12px; color: #637166; }
.metric-summary strong { display: block; margin: 10px 0; font: 28px Georgia, serif; overflow-wrap: anywhere; }
.observation-time, .explanation { font-size: 12px; }
.notice { background: #f1ecd9; color: #746139; padding: 12px 16px; }
.error { color: #a13d2d; }
.empty { padding: 20px 0; }
.observations { overflow-x: auto; }
table { width: 100%; border-collapse: collapse; text-align: left; font-size: 12px; font-variant-numeric: tabular-nums; }
caption { text-align: left; padding: 16px 0; color: #526252; }
th, td { border-bottom: 1px solid #d5dcd0; padding: 12px 10px; white-space: nowrap; }
th { font-weight: 500; color: #526252; }
td small { display: block; color: #637166; margin-top: 5px; }
@media (max-width: 760px) { .analytics-card { padding: 24px; } .heading { align-items: flex-start; flex-wrap: wrap; } .selectors { grid-template-columns: 1fr; } .metric-summary { gap: 12px; } .metric-summary strong { font-size: 23px; } }
</style>
