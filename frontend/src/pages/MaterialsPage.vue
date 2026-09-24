<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { deleteWithCsrf, HttpError, postJsonWithCsrf, postMultipartWithCsrf, request } from '../api/http'

interface MaterialSummary {
  id: string
  title: string
  purpose: string
  sourceUrl: string | null
  fileName: string | null
  segmentCount: number
  kind: 'TEXT' | 'LINK' | 'PDF'
  accountIds: string[]
}
interface MaterialDetail extends MaterialSummary { content: string }
interface Account { id: string; name: string }

const materials = ref<MaterialSummary[]>([])
const accounts = ref<Account[]>([])
const accountIds = ref<string[]>([])
const kind = ref<'TEXT' | 'LINK' | 'PDF'>('TEXT')
const pdfFile = ref<File | null>(null)
const selected = ref<MaterialDetail | null>(null)
const title = ref('')
const purpose = ref('')
const sourceUrl = ref('')
const content = ref('')
const fileName = ref<string | null>(null)
const fileInput = ref<HTMLInputElement | null>(null)
const pending = ref(false)
const error = ref('')
const message = ref('')
const maxBytes = 100 * 1024
const maxPdfBytes = 5 * 1024 * 1024

function validSummary(value: unknown): value is MaterialSummary {
  return typeof value === 'object' && value !== null && 'id' in value && 'title' in value
    && 'purpose' in value && 'sourceUrl' in value && 'fileName' in value && 'segmentCount' in value
    && 'kind' in value && 'accountIds' in value
    && typeof value.id === 'string' && typeof value.title === 'string'
    && typeof value.purpose === 'string' && (value.sourceUrl === null || typeof value.sourceUrl === 'string')
    && (value.fileName === null || typeof value.fileName === 'string')
    && typeof value.segmentCount === 'number' && ['TEXT', 'LINK', 'PDF'].includes(String(value.kind))
    && Array.isArray(value.accountIds) && value.accountIds.every(id => typeof id === 'string')
}

function validDetail(value: unknown): value is MaterialDetail {
  return validSummary(value) && 'content' in value && typeof value.content === 'string'
}

async function loadMaterials() {
  try {
    const result = await request('/api/materials')
    if (!Array.isArray(result) || !result.every(validSummary)) throw new Error('Invalid material list')
    materials.value = result
  } catch {
    error.value = '暂时无法读取资料，请稍后重试。'
  }
}

async function loadAccounts() {
  try {
    const result = await request('/api/accounts')
    if (!Array.isArray(result) || !result.every(value => typeof value === 'object' && value !== null
      && typeof value.id === 'string' && typeof value.name === 'string')) throw new Error('Invalid accounts')
    accounts.value = result
  } catch { error.value = '暂时无法读取账号，请稍后重试。' }
}

function changeKind(event: Event) {
  kind.value = (event.target as HTMLSelectElement).value as 'TEXT' | 'LINK' | 'PDF'
  pdfFile.value = null
  fileName.value = null
  content.value = ''
  if (fileInput.value) fileInput.value.value = ''
}

async function chooseFile(event: Event) {
  const file = (event.target as HTMLInputElement).files?.[0]
  if (!file) return
  error.value = ''
  if (kind.value === 'PDF') {
    if (!/\.pdf$/i.test(file.name)) { error.value = '请选择 PDF 文件。'; return }
    if (file.size > maxPdfBytes) { error.value = 'PDF 文件不能超过 5 MiB。'; return }
    pdfFile.value = file
    fileName.value = file.name
    if (!title.value.trim()) title.value = file.name.replace(/\.pdf$/i, '')
    return
  }
  if (!/\.(txt|md|markdown)$/i.test(file.name)) {
    error.value = '请选择 TXT 或 Markdown 文件。'
    return
  }
  if (file.size > maxBytes) {
    error.value = '文件不能超过 100 KiB。'
    return
  }
  try {
    content.value = await file.text()
    fileName.value = file.name
    if (!title.value.trim()) title.value = file.name.replace(/\.(txt|md|markdown)$/i, '')
  } catch {
    error.value = '无法读取这个文件，请重试。'
  }
}

async function save() {
  if (pending.value) return
  error.value = ''
  message.value = ''
  if (kind.value === 'PDF' && !pdfFile.value) {
    error.value = '请选择可提取文字的 PDF 文件。'
    return
  }
  if (kind.value !== 'PDF' && !content.value.trim()) {
    error.value = '资料内容不能为空。'
    return
  }
  if (kind.value === 'LINK' && !sourceUrl.value.trim()) {
    error.value = '链接资料需要来源链接。'
    return
  }
  if (kind.value !== 'PDF' && new TextEncoder().encode(content.value).length > maxBytes) {
    error.value = '资料不能超过 100 KiB。'
    return
  }
  pending.value = true
  try {
    let result: unknown
    if (kind.value === 'PDF') {
      const form = new FormData()
      form.append('title', title.value)
      form.append('purpose', purpose.value)
      form.append('sourceUrl', sourceUrl.value)
      for (const id of accountIds.value) form.append('accountIds', id)
      form.append('file', pdfFile.value!)
      result = await postMultipartWithCsrf('/api/materials/pdf', form)
    } else {
      result = await postJsonWithCsrf('/api/materials', {
        title: title.value, purpose: purpose.value, sourceUrl: sourceUrl.value,
        fileName: kind.value === 'TEXT' ? fileName.value : null, content: content.value,
        kind: kind.value, accountIds: accountIds.value,
      })
    }
    if (!validDetail(result)) throw new Error('Invalid saved material')
    selected.value = result
    title.value = ''
    purpose.value = ''
    sourceUrl.value = ''
    content.value = ''
    fileName.value = null
    pdfFile.value = null
    accountIds.value = []
    if (fileInput.value) fileInput.value.value = ''
    await loadMaterials()
    message.value = '资料已保存，可以预览。'
  } catch (cause) {
    error.value = cause instanceof HttpError && cause.code === 'MATERIAL_TOO_LARGE'
      ? '资料超出大小限制。' : cause instanceof HttpError && cause.code === 'PDF_TEXT_UNAVAILABLE'
        ? 'PDF 没有可提取文字；扫描件暂不支持。' : cause instanceof HttpError && cause.code === 'INVALID_PDF'
          ? 'PDF 无法解析，请检查文件。' : cause instanceof HttpError && cause.code === 'ACCOUNT_NOT_FOUND'
            ? '关联账号不存在或不属于当前用户。' : cause instanceof HttpError && cause.code === 'UNSUPPORTED_FILE'
        ? '请选择 TXT 或 Markdown 文件。' : cause instanceof HttpError && cause.code === 'INVALID_MATERIAL'
          ? '请检查标题、用途、来源链接和资料内容。' : '暂时无法保存资料，请稍后重试。'
  } finally { pending.value = false }
}

async function preview(id: string) {
  error.value = ''
  try {
    const result = await request(`/api/materials/${id}`)
    if (!validDetail(result) || result.id !== id) throw new Error('Invalid material detail')
    selected.value = result
  } catch {
    error.value = '暂时无法预览资料，请稍后重试。'
  }
}

async function remove(material: MaterialSummary) {
  if (!window.confirm(`确认删除「${material.title}」？删除后无法恢复。`)) return
  error.value = ''
  message.value = ''
  try {
    await deleteWithCsrf(`/api/materials/${material.id}`)
    if (selected.value?.id === material.id) selected.value = null
    await loadMaterials()
    message.value = '资料已删除。'
  } catch {
    error.value = '暂时无法删除资料，请稍后重试。'
  }
}

onMounted(() => { void loadMaterials(); void loadAccounts() })
</script>

<template>
  <section class="materials-card" aria-labelledby="materials-title" :aria-busy="pending">
    <div>
      <span class="section-index">03 / 我的资料</span>
      <h2 id="materials-title">留下值得引用的内容</h2>
      <p>导入文字、可提取文字的 PDF，或保存链接和摘录。来源只保存，不自动访问网页。</p>
      <ul v-if="materials.length" class="material-list">
        <li v-for="material in materials" :key="material.id">
          <div><strong>{{ material.title }}</strong><small>{{ material.kind }} · {{ material.purpose }} · {{ material.segmentCount }} 段</small></div>
          <div class="item-actions">
            <button type="button" @click="preview(material.id)">预览</button>
            <button type="button" @click="remove(material)">删除</button>
          </div>
        </li>
      </ul>
      <p v-else>还没有资料，可以从一段 Java 知识笔记或英语跟读材料开始。</p>
      <div v-if="selected" class="preview" aria-label="资料预览">
        <h3>{{ selected.title }}</h3>
        <a v-if="selected.sourceUrl" :href="selected.sourceUrl" target="_blank" rel="noopener noreferrer">查看来源</a>
        <small v-if="selected.accountIds.length">关联账号：{{ selected.accountIds.map(id => accounts.find(account => account.id === id)?.name ?? id).join('、') }}</small>
        <pre>{{ selected.content }}</pre>
      </div>
    </div>
    <form @submit.prevent="save">
      <h3>导入资料</h3>
      <label for="material-kind">资料类型</label>
      <select id="material-kind" :value="kind" :disabled="pending" @change="changeKind">
        <option value="TEXT">文字 / TXT / Markdown</option>
        <option value="LINK">链接和摘录</option>
        <option value="PDF">文字 PDF</option>
      </select>
      <template v-if="kind !== 'LINK'">
        <label for="material-file">{{ kind === 'PDF' ? 'PDF 文件' : 'TXT / Markdown 文件（可选）' }}</label>
        <input id="material-file" ref="fileInput" type="file" :accept="kind === 'PDF' ? '.pdf,application/pdf' : '.txt,.md,.markdown,text/plain,text/markdown'" @change="chooseFile" />
      </template>
      <label for="material-title">标题</label>
      <input id="material-title" v-model="title" maxlength="200" required :disabled="pending" />
      <label for="material-purpose">用途</label>
      <input id="material-purpose" v-model="purpose" maxlength="80" placeholder="如 Java 面试 / 托福跟读" required :disabled="pending" />
      <label for="material-source">{{ kind === 'LINK' ? '来源链接' : '来源链接（可选）' }}</label>
      <input id="material-source" v-model="sourceUrl" type="url" maxlength="2048" placeholder="https://" :required="kind === 'LINK'" :disabled="pending" />
      <template v-if="kind !== 'PDF'">
        <label for="material-content">{{ kind === 'LINK' ? '用户提供的摘录' : '文字内容' }}</label>
        <textarea id="material-content" v-model="content" rows="8" required :disabled="pending" />
      </template>
      <small>{{ kind === 'PDF' ? (fileName ? `已选择 ${fileName}` : '最多 5 MiB；扫描件暂不支持。') : (fileName ? `已选择 ${fileName}，可继续编辑文字。` : '可直接粘贴；最多 100 KiB。') }}</small>
      <fieldset v-if="accounts.length">
        <legend>关联内容账号（可多选）</legend>
        <label v-for="account in accounts" :key="account.id" class="account-choice">
          <input v-model="accountIds" type="checkbox" :value="account.id" :disabled="pending" />{{ account.name }}
        </label>
      </fieldset>
      <button type="submit" :disabled="pending">{{ pending ? '保存中…' : '保存资料' }}</button>
      <p v-if="message" role="status">{{ message }}</p>
      <p v-if="error" class="error" role="alert">{{ error }}</p>
    </form>
  </section>
</template>

<style scoped>
.materials-card { display: grid; grid-template-columns: 1fr 320px; gap: 40px; margin: 36px 0; border: 1px solid #d5dcd0; border-radius: 4px; background: #fbfaf6; padding: 32px 34px; }
h2 { font-size: 21px; font-weight: 500; margin: 18px 0 12px; }
h3 { font-size: 16px; font-weight: 500; margin: 0 0 8px; }
p, small { color: #637166; font-size: 13px; line-height: 1.8; }
.material-list { list-style: none; padding: 0; margin: 20px 0; }
.material-list li { display: flex; justify-content: space-between; align-items: center; border-top: 1px solid #d5dcd0; gap: 12px; padding: 13px 0; }
.material-list strong, .material-list small { display: block; }
.material-list strong { font-size: 14px; font-weight: 500; }
.material-list small { font-size: 12px; }
.item-actions { display: flex; gap: 6px; }
form { display: grid; align-content: start; gap: 9px; }
label { color: #526252; font-size: 12px; }
input, textarea, select { width: 100%; min-width: 0; border: 1px solid #b7c1b4; border-radius: 4px; background: #faf9f5; color: #263b32; padding: 10px 12px; font: inherit; }
fieldset { border: 1px solid #d5dcd0; margin: 0; }
.account-choice { display: flex; gap: 8px; align-items: center; margin: 6px 0; }
.account-choice input { width: auto; }
textarea { resize: vertical; }
button { cursor: pointer; border: 0; border-radius: 4px; background: #234d3b; color: #fff; padding: 9px 14px; }
button:disabled { cursor: wait; opacity: .6; }
.item-actions button { background: transparent; color: #234d3b; border: 1px solid #b7c1b4; }
.preview { border-top: 1px solid #d5dcd0; margin-top: 18px; padding-top: 18px; }
.preview a { color: #234d3b; text-decoration: underline; font-size: 13px; }
.preview pre { white-space: pre-wrap; overflow-wrap: anywhere; max-height: 320px; overflow: auto; font: 13px/1.8 inherit; }
.error { color: #a13d2d; }
@media (max-width: 760px) { .materials-card { grid-template-columns: 1fr; gap: 16px; padding: 24px; } }
</style>
