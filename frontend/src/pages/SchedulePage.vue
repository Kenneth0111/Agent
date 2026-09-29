<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { HttpError, postJsonWithCsrf, putJsonWithCsrf, request } from '../api/http'

interface Account { id: string; name: string; columns: string[]; weeklyTarget: number }
interface Material { id: string; title: string; accountIds: string[] }
interface Item { id: string; column: string; scheduledDate: string; topicId: string | null; scriptId: string | null;
  version: number; publicationUrl: string | null; externalWorkId: string | null; publishedAt: string | null }
interface Week { id: string; accountId: string; weekStart: string; items: Item[] }
interface Batch { id: string; accountId: string; items: { column: string; topicId: string; scriptId: string }[] }
interface Script { id: string; topicId: string; status: string; version: number;
  script: { spokenText: string; shootingNotes: string; sourceIds: string[] } }
interface Edit { scheduledDate: string; column: string; spokenText: string; shootingNotes: string;
  publicationUrl: string; externalWorkId: string }

function mondayInShanghai(): string {
  const today = new Intl.DateTimeFormat('en-CA', { timeZone: 'Asia/Shanghai', year: 'numeric',
    month: '2-digit', day: '2-digit' }).format(new Date())
  const date = new Date(`${today}T00:00:00Z`)
  date.setUTCDate(date.getUTCDate() - (date.getUTCDay() + 6) % 7)
  return date.toISOString().slice(0, 10)
}

const accounts = ref<Account[]>([])
const materials = ref<Material[]>([])
const accountId = ref('')
const weekStart = ref(mondayInShanghai())
const week = ref<Week | null>(null)
const batches = ref<Batch[]>([])
const batchId = ref('')
const scripts = ref<Record<string, Script>>({})
const edits = ref<Record<string, Edit>>({})
const redo = ref<Record<string, { instruction: string; materialId: string }>>({})
const loading = ref(false)
const pending = ref(false)
const error = ref('')
const notice = ref('')
let loadSequence = 0

const selectedAccount = computed(() => accounts.value.find(account => account.id === accountId.value))
const availableMaterials = computed(() => materials.value.filter(material =>
  material.accountIds.length === 0 || material.accountIds.includes(accountId.value)))
const hasUnsaved = computed(() => week.value?.items.some(item => {
  const edit = edits.value[item.id]
  const script = item.scriptId ? scripts.value[item.scriptId] : undefined
  return edit && (edit.scheduledDate !== item.scheduledDate || edit.column !== item.column
    || edit.publicationUrl !== (item.publicationUrl ?? '') || edit.externalWorkId !== (item.externalWorkId ?? '')
    || (script && (edit.spokenText !== script.script.spokenText || edit.shootingNotes !== script.script.shootingNotes)))
}) ?? false)

function validAccount(value: unknown): value is Account {
  return typeof value === 'object' && value !== null && 'id' in value && typeof value.id === 'string'
    && 'name' in value && typeof value.name === 'string' && 'columns' in value && Array.isArray(value.columns)
    && 'weeklyTarget' in value && typeof value.weeklyTarget === 'number'
}
function validMaterial(value: unknown): value is Material {
  return typeof value === 'object' && value !== null && 'id' in value && typeof value.id === 'string'
    && 'title' in value && typeof value.title === 'string' && 'accountIds' in value
    && Array.isArray(value.accountIds)
}
function validItem(value: unknown): value is Item {
  return typeof value === 'object' && value !== null && 'id' in value && typeof value.id === 'string'
    && 'column' in value && typeof value.column === 'string' && 'scheduledDate' in value
    && typeof value.scheduledDate === 'string' && 'version' in value && typeof value.version === 'number'
    && 'topicId' in value && (value.topicId === null || typeof value.topicId === 'string')
    && 'scriptId' in value && (value.scriptId === null || typeof value.scriptId === 'string')
    && 'publicationUrl' in value && (value.publicationUrl === null || typeof value.publicationUrl === 'string')
    && 'externalWorkId' in value && (value.externalWorkId === null || typeof value.externalWorkId === 'string')
    && 'publishedAt' in value && (value.publishedAt === null || typeof value.publishedAt === 'string')
}
function validWeek(value: unknown): value is Week {
  return typeof value === 'object' && value !== null && 'id' in value && typeof value.id === 'string'
    && 'accountId' in value && typeof value.accountId === 'string' && 'weekStart' in value
    && typeof value.weekStart === 'string' && 'items' in value && Array.isArray(value.items)
    && value.items.every(validItem)
}
function validScript(value: unknown): value is Script {
  return typeof value === 'object' && value !== null && 'id' in value && typeof value.id === 'string'
    && 'topicId' in value && typeof value.topicId === 'string' && 'status' in value
    && typeof value.status === 'string' && 'version' in value && typeof value.version === 'number'
    && 'script' in value && typeof value.script === 'object' && value.script !== null
    && 'spokenText' in value.script && typeof value.script.spokenText === 'string'
    && 'shootingNotes' in value.script && typeof value.script.shootingNotes === 'string'
    && 'sourceIds' in value.script && Array.isArray(value.script.sourceIds)
}
function validBatch(value: unknown): value is Batch {
  return typeof value === 'object' && value !== null && 'id' in value && typeof value.id === 'string'
    && 'accountId' in value && typeof value.accountId === 'string' && 'items' in value
    && Array.isArray(value.items)
}
function resetEdits() {
  const next: Record<string, Edit> = {}
  for (const item of week.value?.items ?? []) {
    const script = item.scriptId ? scripts.value[item.scriptId] : undefined
    next[item.id] = { scheduledDate: item.scheduledDate, column: item.column,
      spokenText: script?.script.spokenText ?? '', shootingNotes: script?.script.shootingNotes ?? '',
      publicationUrl: item.publicationUrl ?? '', externalWorkId: item.externalWorkId ?? '' }
  }
  edits.value = next
}
function scriptChanged(item: Item): boolean {
  const edit = edits.value[item.id]
  const saved = item.scriptId ? scripts.value[item.scriptId] : undefined
  return !!edit && !!saved && (edit.spokenText !== saved.script.spokenText
    || edit.shootingNotes !== saved.script.shootingNotes)
}
async function loadAccounts() {
  try {
    const result = await request('/api/accounts')
    if (!Array.isArray(result) || !result.every(validAccount)) throw new Error('Invalid accounts')
    accounts.value = result
    accountId.value = result[0]?.id ?? ''
  } catch { error.value = '暂时无法读取创作账号。' }
}
async function loadMaterials() {
  try {
    const result = await request('/api/materials')
    if (!Array.isArray(result) || !result.every(validMaterial)) throw new Error('Invalid materials')
    materials.value = result
  } catch { materials.value = [] }
}
async function loadWeek() {
  const sequence = ++loadSequence
  week.value = null
  scripts.value = {}
  batches.value = []
  batchId.value = ''
  edits.value = {}
  redo.value = {}
  error.value = ''
  notice.value = ''
  if (!accountId.value || !weekStart.value) return
  loading.value = true
  try {
    const currentAccount = accountId.value
    const draftResult = await request(`/api/generations/week-plans?accountId=${encodeURIComponent(currentAccount)}`)
    if (sequence !== loadSequence) return
    if (!Array.isArray(draftResult) || !draftResult.every(validBatch)) throw new Error('Invalid batches')
    batches.value = draftResult
    batchId.value = draftResult[0]?.id ?? ''
    let result: unknown
    try {
      result = await request(`/api/schedules/weeks?accountId=${encodeURIComponent(currentAccount)}&weekStart=${weekStart.value}`)
    } catch (cause) {
      if (cause instanceof HttpError && cause.status === 404) return
      throw cause
    }
    if (sequence !== loadSequence) return
    if (!validWeek(result) || result.accountId !== currentAccount) throw new Error('Invalid week')
    const linked = result.items.filter(item => item.scriptId)
    const values = await Promise.all(linked.map(item => request(`/api/generations/scripts/${encodeURIComponent(item.scriptId!)}`)))
    if (sequence !== loadSequence) return
    if (!values.every(validScript)) throw new Error('Invalid scripts')
    scripts.value = Object.fromEntries(values.map(value => [value.id, value]))
    week.value = result
    resetEdits()
    redo.value = Object.fromEntries(result.items.map(item => [item.id, { instruction: '', materialId: '' }]))
  } catch { if (sequence === loadSequence) error.value = '暂时无法读取周计划或草稿，请重试。' }
  finally { if (sequence === loadSequence) loading.value = false }
}
function message(cause: unknown) {
  if (!(cause instanceof HttpError)) return '保存失败，请稍后重试。'
  return ({ VERSION_CONFLICT: '内容已有新版本，请重新加载后核对。', SCRIPT_CONFIRMED: '脚本已确认，请先重新开放编辑。',
    INVALID_WEEK_PLAN: '整周草稿与当前排期的栏目或条数不一致。', PLAN_ITEM_OCCUPIED: '计划项已有草稿，不能覆盖。',
    INVALID_SCHEDULE_DATE: '发布日期须在当前周内。', MODEL_TIMEOUT: '生成超时，请稍后重试。',
    INSUFFICIENT_MATERIAL: '参考资料不足，请先补充资料。', MATERIAL_NOT_FOUND: '所选资料已不可用。',
    SCRIPT_NOT_CONFIRMED: '先确认脚本，才能记录发布作品。',
    SCRIPT_ALREADY_PUBLISHED: '请先取消发布标记，再重新编辑脚本。',
    INVALID_PUBLICATION: '请填写有效的作品链接或作品 ID。' }[cause.code ?? '']) ?? '保存失败，请稍后重试。'
}
function replaceItem(updated: Item) {
  if (week.value) week.value = { ...week.value,
    items: week.value.items.map(item => item.id === updated.id ? updated : item) }
}
async function createWeek() {
  if (pending.value || !accountId.value || !/^\d{4}-\d{2}-\d{2}$/.test(weekStart.value)) return
  pending.value = true; error.value = ''
  try {
    const result = await postJsonWithCsrf('/api/schedules/weeks', { accountId: accountId.value, weekStart: weekStart.value })
    if (!validWeek(result)) throw new Error('Invalid week')
    week.value = result
    resetEdits()
    notice.value = '周计划已保存。'
  } catch (cause) { error.value = message(cause) }
  finally { pending.value = false }
}
async function attachBatch() {
  if (pending.value || !week.value || !batchId.value) return
  pending.value = true; error.value = ''
  try {
    const result = await postJsonWithCsrf(`/api/schedules/weeks/${week.value.id}/attach-batch`, { batchId: batchId.value })
    if (!validWeek(result)) throw new Error('Invalid week')
    await loadWeek()
    notice.value = '整周草稿已关联。'
  } catch (cause) { error.value = message(cause) }
  finally { pending.value = false }
}
async function saveDate(item: Item) {
  const edit = edits.value[item.id]
  if (pending.value || !edit || edit.scheduledDate === item.scheduledDate) return
  pending.value = true; error.value = ''
  try {
    const result = await putJsonWithCsrf(`/api/schedules/items/${item.id}/date`,
      { expectedVersion: item.version, scheduledDate: edit.scheduledDate })
    if (!validItem(result)) throw new Error('Invalid item')
    replaceItem(result)
    notice.value = '发布日期已保存。'
  } catch (cause) { error.value = message(cause) }
  finally { pending.value = false }
}
async function saveColumn(item: Item) {
  const edit = edits.value[item.id]
  if (pending.value || !edit || edit.column === item.column) return
  pending.value = true; error.value = ''
  try {
    const result = await putJsonWithCsrf(`/api/schedules/items/${item.id}/column`,
      { expectedVersion: item.version, column: edit.column })
    if (!validItem(result)) throw new Error('Invalid item')
    replaceItem(result)
    notice.value = '栏目已保存。'
  } catch (cause) { error.value = message(cause) }
  finally { pending.value = false }
}
async function saveScript(item: Item) {
  const edit = edits.value[item.id]
  const current = item.scriptId ? scripts.value[item.scriptId] : undefined
  if (pending.value || !edit || !current || !scriptChanged(item)
    || !edit.spokenText.trim() || !edit.shootingNotes.trim()) return
  pending.value = true; error.value = ''
  try {
    const result = await putJsonWithCsrf(`/api/generations/scripts/${current.id}`,
      { expectedVersion: current.version, spokenText: edit.spokenText, shootingNotes: edit.shootingNotes })
    if (!validScript(result)) throw new Error('Invalid script')
    scripts.value[result.id] = result
    notice.value = '脚本新版本已保存。'
  } catch (cause) { error.value = message(cause) }
  finally { pending.value = false }
}
async function changeScriptStatus(item: Item, action: 'confirm' | 'reopen') {
  const current = item.scriptId ? scripts.value[item.scriptId] : undefined
  if (pending.value || !current || hasUnsaved.value) return
  pending.value = true; error.value = ''
  try {
    const result = await postJsonWithCsrf(`/api/generations/scripts/${current.id}/${action}`,
      { expectedVersion: current.version })
    if (!validScript(result)) throw new Error('Invalid script')
    scripts.value[result.id] = result
    notice.value = action === 'confirm' ? '脚本已确认。' : '脚本已重新开放编辑。'
  } catch (cause) { error.value = message(cause) }
  finally { pending.value = false }
}
async function regenerate(item: Item) {
  const input = redo.value[item.id]
  if (pending.value || hasUnsaved.value || !input?.instruction.trim() || !input.materialId) return
  pending.value = true; error.value = ''
  let saved = false
  try {
    const result = await postJsonWithCsrf(`/api/schedules/items/${item.id}/regenerate`,
      { expectedVersion: item.version, instruction: input.instruction.trim(),
        materialIds: [input.materialId] }, 180_000)
    if (!validItem(result) || !result.scriptId) throw new Error('Invalid item')
    replaceItem(result)
    saved = true
    const script = await request(`/api/generations/scripts/${encodeURIComponent(result.scriptId)}`)
    if (!validScript(script)) throw new Error('Invalid script')
    scripts.value[script.id] = script
    edits.value[item.id] = { ...edits.value[item.id], spokenText: script.script.spokenText,
      shootingNotes: script.script.shootingNotes }
    redo.value[item.id] = { instruction: '', materialId: '' }
    notice.value = '已重做选中内容，其他计划项保持不变。'
  } catch (cause) { error.value = saved ? '内容已重做，但新脚本暂时无法读取，请刷新页面。' : message(cause) }
  finally { pending.value = false }
}
async function savePublication(item: Item, published: boolean) {
  const edit = edits.value[item.id]
  if (pending.value || !edit) return
  pending.value = true; error.value = ''
  try {
    const result = await putJsonWithCsrf(`/api/schedules/items/${item.id}/publication`,
      { expectedVersion: item.version, published, publicationUrl: edit.publicationUrl.trim(),
        externalWorkId: edit.externalWorkId.trim() })
    if (!validItem(result)) throw new Error('Invalid item')
    replaceItem(result)
    edits.value[item.id] = { ...edit, publicationUrl: result.publicationUrl ?? '',
      externalWorkId: result.externalWorkId ?? '' }
    notice.value = published ? '发布记录已保存。' : '已恢复为未发布。'
  } catch (cause) { error.value = message(cause) }
  finally { pending.value = false }
}

watch([accountId, weekStart], loadWeek)
onMounted(() => { loadAccounts(); loadMaterials() })
</script>

<template>
  <section class="schedule-card" aria-labelledby="schedule-title" :aria-busy="loading || pending">
    <div class="schedule-heading">
      <span class="section-index">04 / 周计划</span>
      <h2 id="schedule-title">把三条内容排进这一周</h2>
      <p>为账号选择一周，安排发布日期，再把整周草稿关联到计划项。日期按北京时间计算。</p>
    </div>
    <div class="schedule-controls">
      <label for="schedule-account">创作账号</label>
      <select id="schedule-account" v-model="accountId" :disabled="loading || pending || hasUnsaved">
        <option v-for="account in accounts" :key="account.id" :value="account.id">{{ account.name }}</option>
      </select>
      <label for="schedule-week">周一日期</label>
      <input id="schedule-week" v-model="weekStart" type="date" :disabled="loading || pending || hasUnsaved" />
      <p v-if="hasUnsaved" class="unsaved">有未保存修改。请先保存或取消，再切换账号和周次。</p>
      <button v-if="hasUnsaved" type="button" :disabled="pending" @click="resetEdits">取消未保存修改</button>
      <button v-if="!week && !loading" type="button" :disabled="pending || !accountId" @click="createWeek">创建这周计划</button>
      <template v-if="week && batches.length">
        <label for="schedule-batch">整周草稿</label>
        <select id="schedule-batch" v-model="batchId" :disabled="pending || hasUnsaved">
          <option v-for="batch in batches" :key="batch.id" :value="batch.id">{{ batch.items.map(item => item.column).join(' / ') }} · {{ batch.id.slice(0, 8) }}</option>
        </select>
        <button type="button" :disabled="pending || hasUnsaved || !batchId" @click="attachBatch">关联整周草稿</button>
      </template>
      <p v-if="notice" role="status">{{ notice }}</p>
      <p v-if="error" role="alert" class="error">{{ error }}</p>
    </div>
    <div v-if="week" class="schedule-items">
      <article v-for="(item, index) in week.items" :key="item.id" class="schedule-item">
        <div class="item-head"><strong>第 {{ index + 1 }} 条</strong><span>{{ item.scriptId ? '已关联草稿' : '尚未关联草稿' }}</span></div>
        <label :for="`schedule-date-${item.id}`">发布日期</label>
        <div class="item-field">
          <input :id="`schedule-date-${item.id}`" v-model="edits[item.id].scheduledDate" type="date" :disabled="pending" />
          <button type="button" data-action="save-date" :disabled="pending || edits[item.id].scheduledDate === item.scheduledDate" @click="saveDate(item)">保存日期</button>
        </div>
        <label :for="`schedule-column-${item.id}`">栏目</label>
        <div class="item-field">
          <select :id="`schedule-column-${item.id}`" v-model="edits[item.id].column" :disabled="pending || !!item.scriptId">
            <option v-for="name in selectedAccount?.columns ?? []" :key="name" :value="name">{{ name }}</option>
          </select>
          <button type="button" :disabled="pending || !!item.scriptId || edits[item.id].column === item.column" @click="saveColumn(item)">保存栏目</button>
        </div>
        <template v-if="item.scriptId && scripts[item.scriptId]">
          <p class="script-state">{{ scripts[item.scriptId].status === 'CONFIRMED' ? '已确认' : '待审阅' }} · 版本 {{ scripts[item.scriptId].version }}</p>
          <label :for="`schedule-script-${item.id}`">口播稿</label>
          <textarea :id="`schedule-script-${item.id}`" v-model="edits[item.id].spokenText" rows="5"
            :disabled="pending || scripts[item.scriptId].status === 'CONFIRMED'" />
          <label :for="`schedule-notes-${item.id}`">拍摄建议</label>
          <textarea :id="`schedule-notes-${item.id}`" v-model="edits[item.id].shootingNotes" rows="3"
            :disabled="pending || scripts[item.scriptId].status === 'CONFIRMED'" />
          <div class="item-actions">
            <button v-if="scripts[item.scriptId].status !== 'CONFIRMED'" type="button" data-action="save-script"
              :disabled="pending || !scriptChanged(item) || !edits[item.id].spokenText.trim() || !edits[item.id].shootingNotes.trim()" @click="saveScript(item)">保存脚本</button>
            <button v-if="scripts[item.scriptId].status !== 'CONFIRMED'" type="button" data-action="confirm-script"
              :disabled="pending || hasUnsaved" @click="changeScriptStatus(item, 'confirm')">确认脚本</button>
            <button v-else type="button" :disabled="pending || hasUnsaved || !!item.publishedAt"
              @click="changeScriptStatus(item, 'reopen')">重新编辑</button>
          </div>
        </template>
        <div v-if="redo[item.id]" class="redo-fields">
          <label :for="`schedule-redo-${item.id}`">只重做这一条</label>
          <textarea :id="`schedule-redo-${item.id}`" v-model="redo[item.id].instruction" rows="2"
            maxlength="500" :disabled="pending || scripts[item.scriptId ?? '']?.status === 'CONFIRMED'"
            placeholder="例如：换一个 Java 面试问题" />
          <select v-model="redo[item.id].materialId" :aria-label="`第 ${index + 1} 条参考资料`"
            :disabled="pending || scripts[item.scriptId ?? '']?.status === 'CONFIRMED'">
            <option value="">选择参考资料</option>
            <option v-for="material in availableMaterials" :key="material.id" :value="material.id">{{ material.title }}</option>
          </select>
          <button type="button" data-action="regenerate" :disabled="pending || hasUnsaved || !redo[item.id].instruction.trim()
            || !redo[item.id].materialId || scripts[item.scriptId ?? '']?.status === 'CONFIRMED'"
            @click="regenerate(item)">重新生成这一条</button>
        </div>
        <div class="publication-fields">
          <p>{{ item.publishedAt ? '已发布 · 手动记录' : '未发布' }}</p>
          <label :for="`schedule-publication-url-${item.id}`">作品链接</label>
          <input :id="`schedule-publication-url-${item.id}`" v-model="edits[item.id].publicationUrl"
            type="url" :disabled="pending || !item.scriptId || scripts[item.scriptId]?.status !== 'CONFIRMED'" placeholder="https://..." />
          <label :for="`schedule-work-id-${item.id}`">或作品 ID</label>
          <input :id="`schedule-work-id-${item.id}`" v-model="edits[item.id].externalWorkId"
            type="text" maxlength="128" :disabled="pending || !item.scriptId || scripts[item.scriptId]?.status !== 'CONFIRMED'" />
          <button type="button" data-action="publish" :disabled="pending || !item.scriptId
            || scripts[item.scriptId]?.status !== 'CONFIRMED'
            || (!edits[item.id].publicationUrl.trim() && !edits[item.id].externalWorkId.trim())
            || (!!item.publishedAt && edits[item.id].publicationUrl === (item.publicationUrl ?? '')
              && edits[item.id].externalWorkId === (item.externalWorkId ?? ''))"
            @click="savePublication(item, true)">{{ item.publishedAt ? '更新发布记录' : '标记已发布' }}</button>
          <button v-if="item.publishedAt" type="button" data-action="unpublish" :disabled="pending"
            @click="savePublication(item, false)">取消发布标记</button>
          <a v-if="item.publicationUrl" :href="item.publicationUrl" target="_blank" rel="noopener noreferrer">打开已记录作品 ↗</a>
        </div>
      </article>
    </div>
  </section>
</template>

<style scoped>
.schedule-card { display: grid; grid-template-columns: minmax(0, 1fr) 320px; gap: 28px 40px; margin: 36px 0; border: 1px solid #d5dcd0; border-radius: 4px; background: #fbfaf6; padding: 32px 34px; }
.schedule-heading h2 { font-size: 21px; font-weight: 500; margin: 18px 0 12px; }
p { color: #637166; font-size: 13px; line-height: 1.7; margin: 0; }
.schedule-controls { display: grid; align-content: start; gap: 9px; }
.schedule-items { grid-column: 1 / -1; display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 14px; }
.schedule-item { display: grid; align-content: start; gap: 9px; border: 1px solid #d5dcd0; padding: 18px; min-width: 0; }
.item-head, .item-field, .item-actions { display: flex; align-items: center; gap: 8px; }
.item-head { justify-content: space-between; font-size: 13px; }
.item-head span { color: #637166; font-size: 12px; }
.item-field > input, .item-field > select { flex: 1; min-width: 0; }
.item-actions { flex-wrap: wrap; }
.redo-fields, .publication-fields { display: grid; gap: 8px; border-top: 1px dashed #b7c1b4; padding-top: 12px; }
.publication-fields a { color: #315f46; font-size: 12px; }
label { color: #526252; font-size: 12px; }
input, select, textarea { min-width: 0; width: 100%; border: 1px solid #b7c1b4; border-radius: 4px; padding: 9px; background: #fff; font: inherit; color: #263b32; }
textarea { resize: vertical; }
button { border: 1px solid #315f46; border-radius: 4px; padding: 8px 11px; background: #315f46; color: #fff; cursor: pointer; }
button:disabled { cursor: default; opacity: .5; }
.unsaved, .error { color: #9c4c32; }
.script-state { color: #315f46; }
@media (max-width: 900px) { .schedule-items { grid-template-columns: 1fr 1fr; } }
@media (max-width: 700px) { .schedule-card { grid-template-columns: 1fr; padding: 22px; } .schedule-items { grid-template-columns: 1fr; } }
</style>
