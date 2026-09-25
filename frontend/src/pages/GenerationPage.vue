<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { HttpError, postJsonWithCsrf, request } from '../api/http'

interface Account { id: string; name: string }
interface Material { id: string; title: string; sourceUrl: string | null; accountIds: string[] }
interface Topic { id: string; accountId: string; topic: { column: string; title: string; audience: string; angle: string; hook: string; outline: string; sourceIds: string[]; rationale: string } }
interface Script { id: string; topicId: string; script: { spokenText: string; shootingNotes: string; sourceIds: string[] }; status: string; version: number }
interface Run { id: string; accountId: string; mode: string; status: 'SUCCEEDED' | 'FAILED'; resultId: string | null; errorCode: string | null; failedNode?: string | null }

const accounts = ref<Account[]>([])
const materials = ref<Material[]>([])
const accountId = ref('')
const column = ref('Java 面试')
const instruction = ref('')
const materialIds = ref<string[]>([])
const topics = ref<Topic[]>([])
const topicId = ref('')
const scripts = ref<Script[]>([])
const error = ref('')
const run = ref<Run | null>(null)
const pending = ref(false)
const selectedTopic = computed(() => topics.value.find(topic => topic.id === topicId.value))
const availableMaterials = computed(() => materials.value.filter(material => material.accountIds.length === 0 || material.accountIds.includes(accountId.value)))

function validAccount(value: unknown): value is Account {
  return typeof value === 'object' && value !== null && 'id' in value && 'name' in value
    && typeof value.id === 'string' && typeof value.name === 'string'
}
function validMaterial(value: unknown): value is Material {
  return typeof value === 'object' && value !== null && 'id' in value && 'title' in value
    && 'sourceUrl' in value && 'accountIds' in value && typeof value.id === 'string'
    && typeof value.title === 'string' && (value.sourceUrl === null || typeof value.sourceUrl === 'string')
    && Array.isArray(value.accountIds)
}
function validTopic(value: unknown): value is Topic {
  return typeof value === 'object' && value !== null && 'id' in value && 'accountId' in value && 'topic' in value
    && typeof value.id === 'string' && typeof value.accountId === 'string'
    && typeof value.topic === 'object' && value.topic !== null && 'title' in value.topic
    && typeof value.topic.title === 'string'
}
function validScript(value: unknown): value is Script {
  return typeof value === 'object' && value !== null && 'id' in value && 'script' in value
    && typeof value.id === 'string' && typeof value.script === 'object' && value.script !== null
    && 'spokenText' in value.script && typeof value.script.spokenText === 'string'
}
function validRun(value: unknown): value is Run {
  return typeof value === 'object' && value !== null && 'status' in value && 'id' in value
    && (value.status === 'SUCCEEDED' || value.status === 'FAILED') && typeof value.id === 'string'
}

async function load() {
  pending.value = true
  try {
    const [accountResult, materialResult] = await Promise.all([request('/api/accounts'), request('/api/materials')])
    if (!Array.isArray(accountResult) || !accountResult.every(validAccount)
      || !Array.isArray(materialResult) || !materialResult.every(validMaterial)) throw new Error('Invalid list')
    accounts.value = accountResult
    materials.value = materialResult
    accountId.value = accountResult[0]?.id ?? ''
  } catch { error.value = '暂时无法读取创作账号或资料。' }
  finally { pending.value = false }
}

async function loadTopics() {
  topics.value = []
  topicId.value = ''
  materialIds.value = []
  if (!accountId.value) return
  try {
    const result = await request(`/api/generations/topics?accountId=${encodeURIComponent(accountId.value)}`)
    if (!Array.isArray(result) || !result.every(validTopic)) throw new Error('Invalid topics')
    topics.value = result
    topicId.value = result[0]?.id ?? ''
  } catch { error.value = '暂时无法读取已保存的选题。' }
}

async function loadScripts() {
  scripts.value = []
  if (!topicId.value) return
  try {
    const result = await request(`/api/generations/scripts?topicId=${encodeURIComponent(topicId.value)}`)
    if (!Array.isArray(result) || !result.every(validScript)) throw new Error('Invalid scripts')
    scripts.value = result
  } catch { error.value = '暂时无法读取已保存的脚本。' }
}

function source(id: string) { return materials.value.find(material => material.id === id) }
function failureMessage(code: string | null) {
  return ({ INSUFFICIENT_MATERIAL: '缺少可引用的资料，请先导入资料并重新生成选题。',
    CONTENT_INVALID: '模型输出格式不符合要求。', CONTENT_INVALID_AFTER_CORRECTION: '模型修正后仍未通过校验。',
    SOURCE_NOT_IN_EVIDENCE: '模型引用了未提供的资料。', MODEL_NOT_CONFIGURED: 'DeepSeek 尚未配置。',
    MODEL_TIMEOUT: '模型响应超时。', MODEL_AUTH_FAILED: 'DeepSeek 密钥验证失败。',
    MATERIAL_NOT_FOUND: '所选资料已不可用或不属于当前账号。', TOPIC_NOT_FOUND: '选题已不可用。' }[code ?? '']) ?? '生成失败，请稍后重试。'
}
function nodeLabel(node: string | null | undefined) {
  return ({ readAccount: '读取账号', retrieveEvidence: '检索资料', generateDraft: '模型生成',
    validateDraft: '校验内容', saveDraft: '保存草稿' }[node ?? '']) ?? '生成流程'
}

async function generate(mode: 'TOPICS' | 'SCRIPT') {
  if (pending.value || !accountId.value || !instruction.value.trim() || (mode === 'SCRIPT' && !topicId.value)) return
  pending.value = true
  error.value = ''
  run.value = null
  try {
    const result = await postJsonWithCsrf('/api/generations', { accountId: accountId.value, mode, column: column.value,
      instruction: instruction.value.trim(), materialIds: mode === 'TOPICS' ? materialIds.value : [],
      topicId: mode === 'SCRIPT' ? topicId.value : null }, 65_000)
    if (!validRun(result)) throw new Error('Invalid generation run')
    run.value = result
    if (result.status === 'FAILED') error.value = failureMessage(result.errorCode)
    else if (mode === 'TOPICS') await loadTopics()
    else await loadScripts()
  } catch (cause) {
    error.value = cause instanceof HttpError ? failureMessage(cause.code ?? null) : '暂时无法生成内容，请稍后重试。'
  } finally { pending.value = false }
}

watch(accountId, loadTopics)
watch(topicId, loadScripts)
onMounted(load)
</script>

<template>
  <section class="generation-card" aria-labelledby="generation-title" :aria-busy="pending">
    <div class="heading">
      <span class="section-index">03 / 内容生成</span>
      <h2 id="generation-title">先选题，再写脚本</h2>
      <p>选择当前账号和参考资料，生成 Java 面试或英语跟读选题。脚本只依据选题已引用的资料写作。</p>
    </div>
    <div class="controls">
      <label for="generation-account">创作账号</label>
      <select id="generation-account" v-model="accountId" :disabled="pending">
        <option v-for="account in accounts" :key="account.id" :value="account.id">{{ account.name }}</option>
      </select>
      <label for="generation-column">栏目</label>
      <select id="generation-column" v-model="column" :disabled="pending">
        <option>Java 面试</option><option>英语跟读</option>
      </select>
      <label for="generation-instruction">这条内容想讲什么</label>
      <textarea id="generation-instruction" v-model="instruction" maxlength="500" rows="3" :disabled="pending"
        placeholder="例如：讲清楚 volatile 的可见性，60 秒口播" />
      <fieldset>
        <legend>参考资料（最多 3 份）</legend>
        <p v-if="!availableMaterials.length">暂无资料。可以先生成待核实的选题；写脚本前请导入参考资料。</p>
        <label v-for="material in availableMaterials" :key="material.id" class="material-option">
          <input v-model="materialIds" type="checkbox" :value="material.id"
            :disabled="pending || (materialIds.length >= 3 && !materialIds.includes(material.id))" />{{ material.title }}
        </label>
      </fieldset>
      <button type="button" :disabled="pending || !accountId || !instruction.trim()" @click="generate('TOPICS')">
        {{ pending ? '生成中…' : '生成选题' }}
      </button>
      <p v-if="run" role="status">{{ run.status === 'SUCCEEDED' ? '已保存草稿。' : `${nodeLabel(run.failedNode)}失败（运行 ID：${run.id}）` }}</p>
      <p v-if="error" role="alert" class="error">{{ error }}</p>
    </div>
    <div class="results">
      <label for="generation-topic">已保存选题</label>
      <select id="generation-topic" v-model="topicId" :disabled="pending || !topics.length">
        <option value="">选择选题</option>
        <option v-for="item in topics" :key="item.id" :value="item.id">{{ item.topic.title }}</option>
      </select>
      <article v-if="selectedTopic" class="result">
        <small>{{ selectedTopic.topic.column }} · {{ selectedTopic.topic.audience }}</small>
        <h3>{{ selectedTopic.topic.title }}</h3>
        <p><strong>开头：</strong>{{ selectedTopic.topic.hook }}</p>
        <p><strong>角度：</strong>{{ selectedTopic.topic.angle }}</p>
        <p class="preserve"><strong>提纲：</strong>{{ selectedTopic.topic.outline }}</p>
        <p><strong>依据：</strong>{{ selectedTopic.topic.rationale }}</p>
        <ul class="sources"><li v-for="id in selectedTopic.topic.sourceIds" :key="id">
          <a v-if="source(id)?.sourceUrl" :href="source(id)?.sourceUrl ?? undefined" target="_blank" rel="noopener noreferrer">{{ source(id)?.title ?? id }} ↗</a>
          <span v-else>{{ source(id)?.title ?? id }} · 用户资料</span>
        </li></ul>
        <button type="button" :disabled="pending || !instruction.trim() || !selectedTopic.topic.sourceIds.length" @click="generate('SCRIPT')">{{ pending ? '生成中…' : '按选题写脚本' }}</button>
      </article>
      <article v-for="item in scripts" :key="item.id" class="result script">
        <small>脚本草稿 · 版本 {{ item.version }}</small>
        <p class="preserve">{{ item.script.spokenText }}</p>
        <p class="preserve"><strong>拍摄建议：</strong>{{ item.script.shootingNotes }}</p>
        <ul class="sources"><li v-for="id in item.script.sourceIds" :key="id">{{ source(id)?.title ?? id }}</li></ul>
      </article>
    </div>
  </section>
</template>

<style scoped>
.generation-card { display: grid; grid-template-columns: 1fr 320px 1fr; gap: 28px; padding: 32px 34px; margin: 36px 0; border: 1px solid #d5dcd0; background: #fbfaf6; }
h2 { font-size: 21px; font-weight: 500; margin: 18px 0 12px; }
h3 { font-size: 17px; margin: 10px 0; }
p, li { color: #526252; font-size: 12px; line-height: 1.8; }
.controls, .results { display: grid; align-content: start; gap: 10px; }
label, legend, small { font-size: 12px; color: #526252; }
select, textarea { width: 100%; border: 1px solid #b7c1b4; background: #faf9f5; color: #263b32; padding: 10px; font: inherit; }
fieldset { border: 1px solid #d5dcd0; margin: 0; padding: 10px; max-height: 160px; overflow-y: auto; }
.material-option { display: flex; gap: 8px; align-items: center; margin: 5px 0; }
.material-option input { margin: 0; }
button { border: 0; border-radius: 4px; padding: 12px 15px; background: #234d3b; color: white; text-align: left; cursor: pointer; }
button:disabled { opacity: .6; cursor: wait; }
.result { border-top: 1px solid #d5dcd0; padding-top: 16px; }
.result p { margin: 7px 0; }
.preserve { white-space: pre-wrap; }
.sources { padding-left: 18px; }
.sources a { text-decoration: underline; color: #234d3b; }
.error { color: #a13d2d; }
@media (max-width: 980px) { .generation-card { grid-template-columns: 1fr 1fr; } .heading { grid-column: 1 / -1; } }
@media (max-width: 700px) { .generation-card { grid-template-columns: 1fr; padding: 24px; } .heading { grid-column: auto; } }
</style>
