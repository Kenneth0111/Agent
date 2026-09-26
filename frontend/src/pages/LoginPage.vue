<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { HttpError, postWithCsrf, request } from '../api/http'

interface User { id: number; email: string; displayName: string }
const emit = defineEmits<{ authChange: [signedIn: boolean]; registerRequested: [] }>()
const user = ref<User | null>(null)
const email = ref('')
const password = ref('')
const pending = ref(true)
const error = ref('')

async function loadUser() {
  try {
    const result = await request('/api/auth/me')
    if (typeof result !== 'object' || result === null || !('id' in result) || !('email' in result)
        || !('displayName' in result) || typeof result.id !== 'number'
        || typeof result.email !== 'string' || typeof result.displayName !== 'string') {
      throw new Error('Invalid user response')
    }
    user.value = { id: result.id, email: result.email, displayName: result.displayName }
    emit('authChange', true)
  } catch (cause) {
    if (cause instanceof HttpError && cause.status === 401) {
      user.value = null
      emit('authChange', false)
      return
    }
    throw cause
  }
}

onMounted(async () => {
  try { await loadUser() }
  catch { error.value = '暂时无法连接服务，请稍后重试。' }
  finally { pending.value = false }
})

async function login() {
  if (pending.value) return
  pending.value = true
  error.value = ''
  try {
    await postWithCsrf('/api/auth/login', new URLSearchParams({ email: email.value, password: password.value }).toString())
    await loadUser()
    if (!user.value) error.value = '登录状态已失效，请重新登录。'
  } catch (cause) {
    error.value = cause instanceof HttpError && cause.status === 401
      ? '邮箱或密码不正确，请重新输入。' : '暂时无法连接服务，请稍后重试。'
  } finally {
    password.value = ''
    pending.value = false
  }
}

async function logout() {
  pending.value = true
  error.value = ''
  try {
    await postWithCsrf('/api/auth/logout')
    user.value = null
    emit('authChange', false)
  }
  catch { error.value = '退出失败，请稍后重试。' }
  finally { pending.value = false }
}
</script>

<template>
  <section class="login-card" aria-labelledby="login-title" :aria-busy="pending">
    <div class="login-intro">
      <span class="section-index">你的空间 / YOUR SPACE</span>
      <h2 id="login-title">{{ user ? `欢迎回来，${user.displayName}` : '从你的创作空间开始' }}</h2>
      <p>{{ user ? '登录状态已保存，可以安心开始准备。' : '使用受邀账号登录，整理属于自己的内容。' }}</p>
    </div>
    <div class="login-controls">
      <template v-if="user">
        <p class="signed-in-email">{{ user.email }}</p>
        <button type="button" :disabled="pending" @click="logout">{{ pending ? '正在退出…' : '退出登录' }}</button>
      </template>
      <form v-else @submit.prevent="login">
        <label for="login-email">邮箱</label>
        <input id="login-email" v-model="email" type="email" autocomplete="username" maxlength="254" required :disabled="pending" />
        <label for="login-password">密码</label>
        <input id="login-password" v-model="password" type="password" autocomplete="current-password" required :disabled="pending" />
        <button type="submit" :disabled="pending">{{ pending ? '正在连接…' : '进入工作台' }} <span aria-hidden="true">↗</span></button>
        <button class="register-link" type="button" :disabled="pending" @click="emit('registerRequested')">有邀请码？创建账号</button>
      </form>
      <p v-if="error" class="login-error" role="alert">{{ error }}</p>
    </div>
  </section>
</template>

<style scoped>
.login-card { display: grid; grid-template-columns: 1fr 320px; gap: 40px; border-top: 1px solid #d8ddd3; padding: 36px 0; }
.login-intro h2 { font-size: 22px; font-weight: 500; line-height: 1.5; }
.login-intro p, .signed-in-email { color: #627064; font-size: 13px; line-height: 1.8; overflow-wrap: anywhere; }
form { display: grid; gap: 9px; }
label { font-size: 12px; color: #526252; }
input { min-width: 0; width: 100%; border: 1px solid #b7c1b4; border-radius: 4px; background: #faf9f5; color: #263b32; padding: 11px 12px; font: inherit; }
input:focus-visible { outline: 2px solid #718540; outline-offset: 2px; }
button { cursor: pointer; border: 0; border-radius: 4px; padding: 12px 18px; background: #234d3b; color: #fff; margin-top: 9px; }
button:disabled { cursor: wait; opacity: .6; }
.register-link { background: none; color: #234d3b; padding: 4px 0; text-align: left; }
.login-error { color: #a13d2d; font-size: 13px; line-height: 1.6; }
@media (max-width: 760px) { .login-card { grid-template-columns: 1fr; gap: 16px; } }
</style>
