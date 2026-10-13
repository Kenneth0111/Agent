<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { HttpError, postMultipartWithCsrf } from '../api/http'

const props = defineProps<{ accountId: string }>()
const emit = defineEmits<{ imported: [] }>()
interface Row { rowNumber: number; status: string; errorCode: string | null; redacted?: boolean; data: Record<string, unknown> | null }
interface Preview { canImport: boolean; rows: Row[] }
const kind = ref('metrics')
const file = ref<File | null>(null)
const input = ref<HTMLInputElement>()
const preview = ref<Preview | null>(null)
const rows = ref<Row[]>([])
const pending = ref(false)
const error = ref('')
const notice = ref('')
let revision = 0
const fields = computed(() => kind.value === 'metrics'
  ? ['videoId', 'capturedAt', 'views', 'likes', 'commentCount', 'source']
  : ['videoId', 'commentId', 'text', 'publishedAt', 'capturedAt', 'source'])
const template = computed(() => 'data:text/csv;charset=utf-8,' + encodeURIComponent('\uFEFFaccountId,' + fields.value.join(',') + '\r\n'))
const canConfirm = computed(() => !!file.value && preview.value?.canImport === true && !pending.value)
const labels: Record<string, string> = { videoId: '作品 ID', capturedAt: '观测时间', views: '播放', likes: '点赞',
  commentCount: '评论量', source: '来源', commentId: '评论 ID', text: '脱敏后评论', publishedAt: '评论发布时间' }
const errors: Record<string, string> = {
  ACCOUNT_NOT_FOUND: '账号不存在或无权访问', ACCOUNT_ID_INVALID: '账号 ID 格式错误',
  CSV_EMPTY: '文件为空', CSV_NO_ROWS: '只有表头，没有数据', CSV_TOO_LARGE: '文件超过 1 MiB',
  CSV_TOO_MANY_ROWS: '超过 1000 条记录', CSV_UTF8_INVALID: '请使用 UTF-8 编码',
  CSV_HEADER_INVALID: '表头与模板不一致', CSV_COLUMNS_INVALID: '字段数量不正确', CSV_INVALID: 'CSV 格式错误',
  SNAPSHOT_CONFLICT: '同一观测已有不同指标，请核对', COMMENT_CONFLICT: '同一评论已有不同内容，请核对',
  METRICS_MISSING: '至少填写一个指标', CAPTURED_AT_INVALID: '观测时间须含时区，最多 6 位小数',
  PUBLISHED_AT_INVALID: '评论时间格式错误', VIEWS_INVALID: '播放须为非负整数或留空',
  LIKES_INVALID: '点赞须为非负整数或留空', COMMENT_COUNT_INVALID: '评论量须为非负整数或留空',
  VIDEO_ID_INVALID: '作品 ID 为空或过长', COMMENT_ID_INVALID: '评论 ID 为空或过长',
  COMMENT_TEXT_INVALID: '评论内容为空或过长', SOURCE_INVALID: '来源为空或过长',
}
function message(code: string) { return errors[code] ? `${errors[code]}（${code}）` : code }
function object(value: unknown): value is Record<string, unknown> { return typeof value === 'object' && value !== null }
function validCounts(data: Record<string, unknown>) {
  return ['views', 'likes', 'commentCount'].every(key => data[key] === null
    || typeof data[key] === 'string' && /^\d+$/.test(data[key] as string))
}
function validRows(value: unknown): value is Row[] {
  return Array.isArray(value) && value.length > 0 && value.length <= 1000 && value.every(row => object(row)
    && Number.isInteger(row.rowNumber) && (row.rowNumber as number) > 0
    && ['READY', 'DUPLICATE', 'ERROR', 'IMPORTED'].includes(String(row.status))
    && (row.errorCode === null || typeof row.errorCode === 'string')
    && (row.status === 'ERROR' ? row.data === null && typeof row.errorCode === 'string'
      : object(row.data) && row.data.accountId === props.accountId
        && typeof row.data.videoId === 'string' && typeof row.data.capturedAt === 'string'
        && typeof row.data.source === 'string'
        && (kind.value === 'metrics'
          ? validCounts(row.data)
          : typeof row.data.text === 'string' && typeof row.data.commentId === 'string')))
}
function validPreview(value: unknown): value is Preview {
  return object(value) && typeof value.canImport === 'boolean' && validRows(value.rows)
    && value.rows.every(row => ['READY', 'DUPLICATE', 'ERROR'].includes(row.status))
    && value.canImport === value.rows.every(row => row.status !== 'ERROR')
}
function reset(clearFile = true) {
  revision++
  preview.value = null; rows.value = []; error.value = ''; notice.value = ''; pending.value = false
  if (clearFile) { file.value = null; if (input.value) input.value.value = '' }
}
function choose(event: Event) {
  reset(false)
  file.value = (event.target as HTMLInputElement).files?.[0] ?? null
  if (file.value && (file.value.size === 0 || file.value.size > 1_048_576)) {
    error.value = message(file.value.size === 0 ? 'CSV_EMPTY' : 'CSV_TOO_LARGE')
    file.value = null
  }
}
async function upload(confirm: boolean) {
  if (!file.value || !props.accountId || pending.value || confirm && !canConfirm.value) return
  const current = ++revision
  pending.value = true; error.value = ''; notice.value = ''
  preview.value = null
  const form = new FormData()
  form.set('file', file.value)
  try {
    const result = await postMultipartWithCsrf(`/api/metrics/import/${kind.value === 'comments' ? 'comments/' : ''}${confirm ? 'confirm' : 'preview'}`, form)
    if (current !== revision) return
    if (!confirm) {
      if (!validPreview(result)) throw new Error('Invalid preview or different account')
      preview.value = result; rows.value = result.rows
      notice.value = result.canImport ? '预览通过，尚未写入。确认后才会保存。' : '存在错误，整份文件不能导入。'
    } else {
      if (!object(result) || !Number.isInteger(result.imported) || !Number.isInteger(result.duplicates)
        || (result.imported as number) < 0 || (result.duplicates as number) < 0 || !validRows(result.rows)
        || result.rows.some(row => !['IMPORTED', 'DUPLICATE'].includes(row.status))
        || result.rows.filter(row => row.status === 'IMPORTED').length !== result.imported
        || result.rows.filter(row => row.status === 'DUPLICATE').length !== result.duplicates) throw new Error('Invalid confirmation')
      rows.value = result.rows
      notice.value = `新增 ${result.imported} 条，跳过重复 ${result.duplicates} 条。`
      emit('imported')
    }
  } catch (cause) {
    if (current !== revision) return
    if (confirm && cause instanceof HttpError && cause.status === 422 && validPreview(cause.body)) {
      preview.value = cause.body; rows.value = cause.body.rows
      error.value = '确认时重新校验未通过，整份文件未写入。请按最新错误修改文件。'
    } else {
      rows.value = []
      error.value = cause instanceof HttpError && cause.status === 401 ? '登录已失效，请重新登录。'
        : cause instanceof HttpError && cause.status === 413 ? message('CSV_TOO_LARGE')
          : confirm ? '尚无法确认保存结果。请重新预览；已保存的相同记录会跳过，不会重复插入。'
            : '无法预览。请检查网络、文件格式及所有记录的账号 ID 是否与当前账号一致。'
    }
  } finally { if (current === revision) pending.value = false }
}
watch(() => props.accountId, () => reset())
onBeforeUnmount(() => { revision++ })
</script>

<template>
  <section class="import-panel" aria-label="导入观测数据" :aria-busy="pending">
    <div class="import-heading"><h3>01 / 导入观测数据</h3><a :href="template" :download="`${kind}-template.csv`">下载空白 CSV 模板 ↗</a></div>
    <p>UTF-8 · 最多 1 MiB / 1000 条。本次文件只导入当前账号，未知指标留空，真实零值填写 0。演示数据请在来源中标记 DEMO。</p>
    <label>当前账号 ID（填入每行 accountId）<input :value="accountId" readonly aria-label="当前账号 ID" @focus="($event.target as HTMLInputElement).select()"></label>
    <div class="upload-fields">
      <label>数据类型<select v-model="kind" :disabled="pending" @change="reset()"><option value="metrics">作品指标</option><option value="comments">评论样本</option></select></label>
      <label>选择 CSV 文件<input ref="input" type="file" accept=".csv,text/csv" :disabled="pending || !accountId" @change="choose"></label>
    </div>
    <p v-if="kind === 'comments'">预览仅展示脱敏后内容；邮箱和常见手机号会被替换，上传前请自行去除姓名、地址等其他个人信息。</p>
    <div class="actions"><button data-action="preview" type="button" :disabled="pending || !file || !accountId" @click="upload(false)">预览文件</button>
      <button data-action="confirm" type="button" :disabled="!canConfirm" @click="upload(true)">确认导入</button></div>
    <p v-if="pending" role="status">{{ kind === 'metrics' ? '指标' : '评论' }}文件处理中…</p>
    <p v-if="error" class="error" role="alert">{{ error }}</p><p v-if="notice" role="status">{{ notice }}</p>
    <div v-if="rows.length" class="preview-table" tabindex="0" aria-label="CSV 预览，可横向滚动">
      <table><caption>服务端校验结果 · 记录编号包含表头（多行评论仍计为一条记录）</caption>
        <thead><tr><th>记录</th><th>状态 / 错误</th><th v-for="key in fields" :key="key">{{ labels[key] }}</th></tr></thead>
        <tbody><tr v-for="row in rows" :key="row.rowNumber"><td>{{ row.rowNumber }}</td>
          <td>{{ row.errorCode ? message(row.errorCode) : row.status === 'DUPLICATE' ? '重复，跳过' : row.status === 'IMPORTED' ? '已导入' : '待确认' }}<small v-if="row.redacted">已脱敏</small></td>
          <td v-for="key in fields" :key="key" :class="{ comment: key === 'text' }">{{ row.data ? row.data[key] ?? (['views', 'likes', 'commentCount'].includes(key) ? '未知' : '—') : '—' }}</td>
        </tr></tbody>
      </table>
    </div>
  </section>
</template>

<style scoped>
.import-panel { margin: 28px 0; padding-top: 24px; border-top: 1px solid #d5dcd0; min-width: 0; }
.import-heading, .actions { display: flex; flex-wrap: wrap; gap: 12px; align-items: center; justify-content: space-between; }
h3 { font-size: 15px; font-weight: 500; color: #234d3b; }
p, caption { font-size: 12px; color: #637166; line-height: 1.8; }
a { color: #234d3b; font-size: 12px; text-underline-offset: 4px; }
label { display: grid; gap: 9px; font-size: 12px; color: #526252; min-width: 0; }
input, select { box-sizing: border-box; width: 100%; padding: 10px; font: inherit; border: 1px solid #b7c1b4; border-radius: 4px; background: #faf9f5; color: #263b32; }
.upload-fields { display: grid; grid-template-columns: 1fr 2fr; gap: 18px; margin: 18px 0; }
.actions { justify-content: flex-start; }
button { padding: 9px 14px; background: transparent; color: #234d3b; border: 1px solid #b7c1b4; border-radius: 4px; cursor: pointer; }
button[data-action=confirm] { background: #234d3b; color: #fbfaf6; }
button:disabled { opacity: .45; cursor: default; }
.error { color: #a13d2d; }
.preview-table { overflow-x: auto; margin-top: 16px; }
table { border-collapse: collapse; width: 100%; font-size: 12px; text-align: left; font-variant-numeric: tabular-nums; }
caption { text-align: left; margin-bottom: 10px; }
th, td { padding: 12px 10px; border-bottom: 1px solid #d5dcd0; white-space: nowrap; }
th { font-weight: 500; color: #526252; }
td.comment { white-space: pre-wrap; min-width: 180px; max-width: 360px; overflow-wrap: anywhere; }
small { display: block; color: #637166; margin-top: 5px; }
:focus-visible { outline: 2px solid #577c39; outline-offset: 3px; }
@media (max-width: 760px) { .upload-fields { grid-template-columns: 1fr; } }
</style>
