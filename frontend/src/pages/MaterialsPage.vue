<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { deleteWithCsrf, HttpError, postJsonWithCsrf, request } from '../api/http'

interface MaterialSummary {
  id: string
  title: string
  purpose: string
  sourceUrl: string | null
  fileName: string | null
  segmentCount: number
}
interface MaterialDetail extends MaterialSummary { content: string }

const materials = ref<MaterialSummary[]>([])
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

function validSummary(value: unknown): value is MaterialSummary {
  return typeof value === 'object' && value !== null && 'id' in value && 'title' in value
    && 'purpose' in value && 'sourceUrl' in value && 'fileName' in value && 'segmentCount' in value
    && typeof value.id === 'string' && typeof value.title === 'string'
    && typeof value.purpose === 'string' && (value.sourceUrl === null || typeof value.sourceUrl === 'string')
    && (value.fileName === null || typeof value.fileName === 'string')
    && typeof value.segmentCount === 'number'
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

async function chooseFile(event: Event) {
  const file = (event.target as HTMLInputElement).files?.[0]
  if (!file) return
  error.value = ''
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
  if (!content.value.trim()) {
    error.value = '资料内容不能为空。'
    return
  }
  if (new TextEncoder().encode(content.value).length > maxBytes) {
    error.value = '资料不能超过 100 KiB。'
    return
  }
  pending.value = true
  try {
    const result = await postJsonWithCsrf('/api/materials', {
      title: title.value, purpose: purpose.value, sourceUrl: sourceUrl.value,
      fileName: fileName.value, content: content.value,
    })
    if (!validDetail(result)) throw new Error('Invalid saved material')
    selected.value = result
    title.value = ''
    purpose.value = ''
    sourceUrl.value = ''
    content.value = ''
    fileName.value = null
    if (fileInput.value) fileInput.value.value = ''
    await loadMaterials()
    message.value = '资料已保存，可以预览。'
  } catch (cause) {
    error.value = cause instanceof HttpError && cause.code === 'MATERIAL_TOO_LARGE'
      ? '资料不能超过 100 KiB。' : cause instanceof HttpError && cause.code === 'UNSUPPORTED_FILE'
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

onMounted(loadMaterials)
</script>

<template>
  <section class="materials-card" aria-labelledby="materials-title" :aria-busy="pending">
    <div>
      <span class="section-index">03 / 我的资料</span>
      <h2 id="materials-title">留下值得引用的内容</h2>
      <p>粘贴文字或选择 TXT、Markdown 文件。保留出处，后续选题时才能核对依据。</p>
      <ul v-if="materials.length" class="material-list">
        <li v-for="material in materials" :key="material.id">
          <div><strong>{{ material.title }}</strong><small>{{ material.purpose }} · {{ material.segmentCount }} 段</small></div>
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
        <pre>{{ selected.content }}</pre>
      </div>
    </div>
    <form @submit.prevent="save">
      <h3>导入文字资料</h3>
      <label for="material-file">TXT / Markdown 文件</label>
      <input id="material-file" ref="fileInput" type="file" accept=".txt,.md,.markdown,text/plain,text/markdown" @change="chooseFile" />
      <label for="material-title">标题</label>
      <input id="material-title" v-model="title" maxlength="200" required :disabled="pending" />
      <label for="material-purpose">用途</label>
      <input id="material-purpose" v-model="purpose" maxlength="80" placeholder="如 Java 面试 / 托福跟读" required :disabled="pending" />
      <label for="material-source">来源链接（可选）</label>
      <input id="material-source" v-model="sourceUrl" type="url" maxlength="2048" placeholder="https://" :disabled="pending" />
      <label for="material-content">文字内容</label>
      <textarea id="material-content" v-model="content" rows="8" required :disabled="pending" />
      <small>{{ fileName ? `已选择 ${fileName}，可继续编辑文字。` : '可直接粘贴；最多 100 KiB。' }}</small>
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
input, textarea { width: 100%; min-width: 0; border: 1px solid #b7c1b4; border-radius: 4px; background: #faf9f5; color: #263b32; padding: 10px 12px; font: inherit; }
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
