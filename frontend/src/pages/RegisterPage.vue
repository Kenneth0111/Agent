<script setup lang="ts">
import { ref } from 'vue'
import { HttpError, postJsonWithCsrf } from '../api/http'

const emit = defineEmits<{ back: [] }>()
const invitationCode = ref('')
const email = ref('')
const displayName = ref('')
const password = ref('')
const pending = ref(false)
const error = ref('')
const registered = ref(false)

async function register() {
  if (pending.value) return
  pending.value = true
  error.value = ''
  try {
    await postJsonWithCsrf('/api/auth/register', {
      invitationCode: invitationCode.value,
      email: email.value,
      displayName: displayName.value,
      password: password.value,
    })
    password.value = ''
    registered.value = true
  } catch (cause) {
    error.value = cause instanceof HttpError && cause.status === 400
      ? '邀请码无效、已过期或已使用，请核对后重试。' : '暂时无法连接服务，请稍后重试。'
  } finally {
    pending.value = false
  }
}
</script>

<template>
  <section class="register-card" aria-labelledby="register-title" :aria-busy="pending">
    <div>
      <span class="section-index">受邀注册 / INVITATION</span>
      <h2 id="register-title">创建你的创作空间</h2>
      <p>邀请码仅能使用一次。注册完成后使用邮箱和密码登录。</p>
    </div>
    <div>
      <div v-if="registered" class="register-result">
        <p>注册成功，请登录后开始整理内容。</p>
        <button type="button" @click="emit('back')">去登录 <span aria-hidden="true">↗</span></button>
      </div>
      <form v-else @submit.prevent="register">
        <label for="invite-code">邀请码</label>
        <input id="invite-code" v-model="invitationCode" name="invitationCode" autocomplete="off" minlength="8" maxlength="128" required :disabled="pending" />
        <label for="register-email">邮箱</label>
        <input id="register-email" v-model="email" name="email" type="email" autocomplete="email" maxlength="254" required :disabled="pending" />
        <label for="display-name">称呼</label>
        <input id="display-name" v-model="displayName" name="displayName" autocomplete="name" maxlength="80" required :disabled="pending" />
        <label for="register-password">密码</label>
        <input id="register-password" v-model="password" name="password" type="password" autocomplete="new-password" minlength="12" maxlength="200" required :disabled="pending" />
        <button type="submit" :disabled="pending">{{ pending ? '正在创建…' : '创建账号' }} <span aria-hidden="true">↗</span></button>
        <button class="text-button" type="button" :disabled="pending" @click="emit('back')">返回登录</button>
      </form>
      <p v-if="error" class="register-error" role="alert">{{ error }}</p>
    </div>
  </section>
</template>

<style scoped>
.register-card { display: grid; grid-template-columns: 1fr 320px; gap: 40px; border-top: 1px solid #d8ddd3; padding: 36px 0; }
.register-card h2 { font-size: 22px; font-weight: 500; line-height: 1.5; }
.register-card p { color: #627064; font-size: 13px; line-height: 1.8; }
form { display: grid; gap: 9px; }
label { font-size: 12px; color: #526252; }
input { min-width: 0; width: 100%; border: 1px solid #b7c1b4; border-radius: 4px; background: #faf9f5; color: #263b32; padding: 11px 12px; font: inherit; }
input:focus-visible { outline: 2px solid #718540; outline-offset: 2px; }
button { cursor: pointer; border: 0; border-radius: 4px; padding: 12px 18px; background: #234d3b; color: #fff; margin-top: 9px; }
button:disabled { cursor: wait; opacity: .6; }
.text-button { background: none; color: #234d3b; padding: 4px 0; text-align: left; }
.register-result { display: grid; gap: 12px; }
.register-error { color: #a13d2d; font-size: 13px; line-height: 1.6; }
@media (max-width: 760px) { .register-card { grid-template-columns: 1fr; gap: 16px; } }
</style>
