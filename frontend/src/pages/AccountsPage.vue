<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { HttpError, postJsonWithCsrf, putJsonWithCsrf, request } from '../api/http'

interface Account {
  id: string
  name: string
  audience: string
  positioning: string
  columns: string[]
  weeklyTarget: number
}

const emit = defineEmits<{ changed: [] }>()
const accounts = ref<Account[]>([])
const editingId = ref('')
const name = ref('')
const audience = ref('')
const positioning = ref('')
const columns = ref('')
const weeklyTarget = ref(3)
const pending = ref(false)
const error = ref('')
const message = ref('')

function validAccount(value: unknown): value is Account {
  return typeof value === 'object' && value !== null && 'id' in value && 'name' in value
    && 'audience' in value && 'positioning' in value && 'columns' in value && 'weeklyTarget' in value
    && typeof value.id === 'string' && typeof value.name === 'string'
    && typeof value.audience === 'string' && typeof value.positioning === 'string'
    && Array.isArray(value.columns) && value.columns.every(column => typeof column === 'string')
    && typeof value.weeklyTarget === 'number'
}

async function loadAccounts() {
  try {
    const result = await request('/api/accounts')
    if (!Array.isArray(result) || !result.every(validAccount)) throw new Error('Invalid accounts response')
    accounts.value = result
  } catch {
    error.value = '暂时无法读取账号，请稍后重试。'
  }
}

function edit(account: Account) {
  editingId.value = account.id
  name.value = account.name
  audience.value = account.audience
  positioning.value = account.positioning
  columns.value = account.columns.join('、')
  weeklyTarget.value = account.weeklyTarget
  error.value = ''
  message.value = ''
}

function clearForm() {
  editingId.value = ''
  name.value = ''
  audience.value = ''
  positioning.value = ''
  columns.value = ''
  weeklyTarget.value = 3
}

async function save() {
  if (pending.value) return
  pending.value = true
  error.value = ''
  message.value = ''
  const input = {
    name: name.value,
    audience: audience.value,
    positioning: positioning.value,
    columns: columns.value.split(/[、,，\n]/).map(column => column.trim()).filter(Boolean),
    weeklyTarget: weeklyTarget.value,
  }
  try {
    const result = editingId.value
      ? await putJsonWithCsrf(`/api/accounts/${editingId.value}`, input)
      : await postJsonWithCsrf('/api/accounts', input)
    if (!validAccount(result)) throw new Error('Invalid account response')
    message.value = editingId.value ? '账号配置已更新。' : '账号已创建，可以开始准备内容。'
    clearForm()
    await loadAccounts()
    emit('changed')
  } catch (cause) {
    error.value = cause instanceof HttpError && cause.code === 'INVALID_ACCOUNT'
      ? '请检查名称、受众、定位、栏目和每周条数。' : cause instanceof HttpError && cause.status === 404
        ? '该账号已不可用，请刷新页面。' : '暂时无法保存账号，请稍后重试。'
  } finally { pending.value = false }
}

onMounted(loadAccounts)
</script>

<template>
  <section class="accounts-card" aria-labelledby="accounts-title" :aria-busy="pending">
    <div>
      <span class="section-index">02 / 内容账号</span>
      <h2 id="accounts-title">先为账号定个方向</h2>
      <p>抖音账号尚未注册也可以先建档，后续的选题和内容计划会参考这里的配置。</p>
      <ul v-if="accounts.length" class="account-list">
        <li v-for="account in accounts" :key="account.id">
          <div><strong>{{ account.name }}</strong><small>{{ account.audience }} · 每周 {{ account.weeklyTarget }} 条</small></div>
          <button type="button" :disabled="pending" @click="edit(account)">编辑</button>
        </li>
      </ul>
      <p v-else>还没有内容账号，可以从第一个开始。</p>
    </div>
    <form @submit.prevent="save">
      <h3>{{ editingId ? '编辑账号' : '新建账号' }}</h3>
      <label for="account-name">账号名称</label>
      <input id="account-name" v-model="name" name="name" maxlength="80" required :disabled="pending" />
      <label for="account-audience">目标受众</label>
      <input id="account-audience" v-model="audience" name="audience" maxlength="160" required :disabled="pending" />
      <label for="account-positioning">内容定位</label>
      <textarea id="account-positioning" v-model="positioning" name="positioning" maxlength="500" required :disabled="pending" />
      <label for="account-columns">栏目（用顿号或逗号分开）</label>
      <input id="account-columns" v-model="columns" name="columns" required :disabled="pending" />
      <label for="weekly-target">每周计划条数</label>
      <input id="weekly-target" v-model.number="weeklyTarget" name="weeklyTarget" type="number" min="1" max="21" required :disabled="pending" />
      <div class="actions">
        <button type="submit" :disabled="pending">{{ pending ? '保存中…' : '保存账号' }}</button>
        <button v-if="editingId" class="secondary" type="button" :disabled="pending" @click="clearForm">取消编辑</button>
      </div>
      <p v-if="message" role="status">{{ message }}</p>
      <p v-if="error" class="error" role="alert">{{ error }}</p>
    </form>
  </section>
</template>

<style scoped>
.accounts-card { display: grid; grid-template-columns: 1fr 320px; gap: 40px; margin: 36px 0; border: 1px solid #d5dcd0; border-radius: 4px; background: #fbfaf6; padding: 32px 34px; }
h2 { font-size: 21px; font-weight: 500; margin: 18px 0 12px; }
h3 { font-size: 16px; font-weight: 500; margin: 0 0 8px; }
p { color: #637166; font-size: 13px; line-height: 1.8; }
.account-list { list-style: none; padding: 0; margin: 20px 0 0; }
.account-list li { display: flex; justify-content: space-between; align-items: center; border-top: 1px solid #d5dcd0; gap: 12px; padding: 13px 0; }
.account-list strong, .account-list small { display: block; }
.account-list strong { font-size: 14px; font-weight: 500; }
.account-list small { color: #637166; font-size: 12px; margin-top: 3px; }
form { display: grid; align-content: start; gap: 9px; }
label { color: #526252; font-size: 12px; }
input, textarea { width: 100%; min-width: 0; border: 1px solid #b7c1b4; border-radius: 4px; background: #faf9f5; color: #263b32; padding: 10px 12px; font: inherit; }
textarea { min-height: 82px; resize: vertical; }
button { cursor: pointer; border: 0; border-radius: 4px; background: #234d3b; color: #fff; padding: 9px 14px; }
button:disabled { cursor: wait; opacity: .6; }
.account-list button, .secondary { background: transparent; color: #234d3b; border: 1px solid #b7c1b4; }
.actions { display: flex; gap: 9px; margin-top: 4px; }
.error { color: #a13d2d; }
@media (max-width: 760px) { .accounts-card { grid-template-columns: 1fr; gap: 16px; padding: 24px; } }
</style>
