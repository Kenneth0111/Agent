<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { HttpError, putJsonWithCsrf, request } from '../api/http'

interface Settings {
  emailRecipient: string | null; smtpFromEmail: string | null; emailEnabled: boolean
  smtpHost: string | null; smtpPort: number | null; smtpUsernameConfigured: boolean
  smtpPasswordConfigured: boolean; feishuEnabled: boolean
  feishuWebhookConfigured: boolean; feishuSecretConfigured: boolean
}
interface Delivery {
  id: string; runId: string; channel: 'EMAIL' | 'FEISHU'; status: string
  errorCode: string | null; createdAt: string
}

const settings = ref<Settings | null>(null)
const deliveries = ref<Delivery[]>([])
const form = ref({ emailEnabled: false, emailRecipient: '', smtpFromEmail: '', smtpHost: '', smtpPort: 587,
  smtpUsername: '', smtpPassword: '', feishuEnabled: false, feishuWebhook: '', feishuSecret: '' })
const busy = ref(false)
const error = ref('')
const notice = ref('')

function validSettings(value: unknown): value is Settings {
  return typeof value === 'object' && value !== null && 'emailEnabled' in value
    && 'feishuEnabled' in value && 'smtpUsernameConfigured' in value
    && typeof value.emailEnabled === 'boolean' && typeof value.feishuEnabled === 'boolean'
}

async function load() {
  busy.value = true
  error.value = ''
  try {
    const [saved, recent] = await Promise.all([
      request('/api/notification-settings'), request('/api/notification-settings/deliveries'),
    ])
    if (!validSettings(saved) || !Array.isArray(recent)) throw new Error('Invalid notification data')
    settings.value = saved
    deliveries.value = recent as Delivery[]
    form.value = { emailEnabled: saved.emailEnabled, emailRecipient: saved.emailRecipient ?? '',
      smtpFromEmail: saved.smtpFromEmail ?? '', smtpHost: saved.smtpHost ?? '',
      smtpPort: saved.smtpPort ?? 587, smtpUsername: '', smtpPassword: '',
      feishuEnabled: saved.feishuEnabled, feishuWebhook: '', feishuSecret: '' }
  } catch { error.value = '暂时无法读取通知配置，请稍后重试。' }
  finally { busy.value = false }
}

async function save() {
  busy.value = true
  error.value = ''
  notice.value = ''
  try {
    const saved = await putJsonWithCsrf('/api/notification-settings', {
      ...form.value, smtpUsername: form.value.smtpUsername || null,
      smtpPassword: form.value.smtpPassword || null,
      feishuWebhook: form.value.feishuWebhook || null,
      feishuSecret: form.value.feishuSecret || null,
    })
    if (!validSettings(saved)) throw new Error('Invalid notification settings')
    settings.value = saved
    form.value.smtpUsername = ''
    form.value.smtpPassword = ''
    form.value.feishuWebhook = ''
    form.value.feishuSecret = ''
    notice.value = '通知设置已保存。新生成的整周草稿将使用已启用的渠道。'
  } catch (cause) {
    error.value = cause instanceof HttpError && cause.code === 'INVALID_NOTIFICATION_SETTINGS'
      ? '请核对已启用渠道的地址、端口和凭据。' : '保存失败，请稍后重试。'
  } finally { busy.value = false }
}

async function refreshDeliveries() {
  busy.value = true
  error.value = ''
  try {
    const recent = await request('/api/notification-settings/deliveries')
    if (!Array.isArray(recent)) throw new Error('Invalid deliveries')
    deliveries.value = recent as Delivery[]
  } catch { error.value = '暂时无法读取投递状态，请稍后重试。' }
  finally { busy.value = false }
}

function state(status: string): string {
  return ({ PENDING: '待发送', SENDING: '发送中', RETRY: '等待重试', SENT: '已发送',
    FAILED: '发送失败', UNKNOWN: '结果待核对' }[status]) ?? status
}

onMounted(load)
</script>

<template>
  <section class="notifications" aria-labelledby="notifications-title">
    <div class="section-heading"><div><span class="section-index">07 / 通知设置</span>
      <h2 id="notifications-title">草稿完成后，提醒你来看。</h2>
      <p>可选邮件、飞书、两者同时开启，或暂时关闭。通知只发送摘要和工作台链接。</p></div>
      <button type="button" class="quiet" :disabled="busy" @click="settings ? refreshDeliveries() : load()">刷新状态</button>
    </div>
    <p v-if="error" class="message error" role="alert">{{ error }}</p>
    <p v-if="notice" class="message" role="status">{{ notice }}</p>

    <form v-if="settings" @submit.prevent="save">
      <div class="channels">
        <fieldset><legend>邮件提醒</legend>
          <label class="switch"><input v-model="form.emailEnabled" type="checkbox" :disabled="busy" />启用邮件</label>
          <label>收件邮箱<input v-model.trim="form.emailRecipient" type="email" maxlength="254" autocomplete="off" :disabled="busy" /></label>
          <label>发件邮箱<input v-model.trim="form.smtpFromEmail" type="email" maxlength="254" autocomplete="off" :disabled="busy" /></label>
          <div class="host-row"><label>SMTP 服务器<input v-model.trim="form.smtpHost" maxlength="255" autocomplete="off" :disabled="busy" /></label>
            <label>端口<input v-model.number="form.smtpPort" type="number" min="1" max="65535" :disabled="busy" /></label></div>
          <label>SMTP 用户名<input v-model="form.smtpUsername" autocomplete="off" :disabled="busy"
            :placeholder="settings.smtpUsernameConfigured ? '已保存；留空则保留' : '填写 SMTP 用户名'" /></label>
          <label>SMTP 密码<input v-model="form.smtpPassword" type="password" autocomplete="new-password" :disabled="busy"
            :placeholder="settings.smtpPasswordConfigured ? '已保存；留空则保留' : '填写 SMTP 密码或授权码'" /></label>
          <p>需要支持 STARTTLS 的 SMTP 服务。密码保存后不会再次显示。</p>
        </fieldset>
        <fieldset><legend>飞书机器人</legend>
          <label class="switch"><input v-model="form.feishuEnabled" type="checkbox" :disabled="busy" />启用飞书</label>
          <label>机器人 Webhook<input v-model="form.feishuWebhook" type="password" autocomplete="off" :disabled="busy"
            :placeholder="settings.feishuWebhookConfigured ? '已保存；留空则保留' : 'https://open.feishu.cn/open-apis/bot/v2/hook/…'" /></label>
          <label>签名密钥<input v-model="form.feishuSecret" type="password" autocomplete="new-password" :disabled="busy"
            :placeholder="settings.feishuSecretConfigured ? '已保存；留空则保留' : '填写机器人的签名密钥'" /></label>
          <p>Webhook 和签名密钥保存后不会再次显示。请用自己指定的测试群验收。</p>
        </fieldset>
      </div>
      <button type="submit" :disabled="busy">{{ busy ? '保存中…' : '保存通知设置' }}</button>
    </form>

    <div class="history"><div class="history-head"><h3>最近投递</h3><span>只显示你的投递记录</span></div>
      <p v-if="!deliveries.length" class="empty">暂无投递记录。启用渠道后，成功的周任务会自动创建通知。</p>
      <ul v-else><li v-for="delivery in deliveries" :key="delivery.id">
        <div><strong>{{ delivery.channel === 'EMAIL' ? '邮件' : '飞书' }} · {{ state(delivery.status) }}</strong>
          <small>{{ new Date(delivery.createdAt).toLocaleString('zh-CN') }}<span v-if="delivery.errorCode"> · {{ delivery.errorCode }}</span></small></div>
        <a :href="`/?run=${encodeURIComponent(delivery.runId)}`">查看草稿 ↗</a>
      </li></ul>
    </div>
  </section>
</template>

<style scoped>
.notifications { margin: 44px 0; color: #263b32; }
.section-heading,.history-head { display: flex; justify-content: space-between; align-items: start; gap: 20px; }
.section-heading { border-top: 2px solid #315f46; padding: 25px 0 21px; }
.section-index { font: 11px Consolas,monospace; letter-spacing: 1.5px; color: #718540; }
h2 { font: 400 27px 'STSong','SimSun',serif; margin: 11px 0; } h3 { font-size: 18px; font-weight: 500; margin: 0; }
p { font-size: 12px; line-height: 1.7; color: #667466; margin: 0; }
.channels { display: grid; grid-template-columns: repeat(2,minmax(0,1fr)); gap: 14px; margin-bottom: 14px; }
fieldset,.history { border: 1px solid #d5dcd0; border-radius: 4px; background: #fbfaf6; padding: 20px; min-width: 0; }
fieldset { display: grid; align-content: start; gap: 13px; margin: 0; } legend { font-size: 16px; font-weight: 600; padding: 0 6px; }
label { display: grid; gap: 6px; font-size: 12px; color: #526252; } input { width: 100%; min-width: 0; border: 1px solid #b7c1b4; border-radius: 4px; padding: 9px; background: #fff; color: #263b32; font: inherit; }
label.switch { display: flex; align-items: center; gap: 8px; font-size: 13px; } .switch input { width: auto; }
.host-row { display: grid; grid-template-columns: 2fr 1fr; gap: 10px; }
button { border: 1px solid #315f46; border-radius: 4px; padding: 9px 13px; background: #315f46; color: #fff; cursor: pointer; font: inherit; font-size: 12px; }
button.quiet { background: transparent; color: #315f46; } button:disabled { opacity: .5; cursor: default; }
.history { margin-top: 16px; } .history-head { align-items: center; margin-bottom: 10px; }.history-head span,small { color: #718071; font-size: 11px; }
ul { list-style: none; margin: 0; padding: 0; } li { display: flex; justify-content: space-between; gap: 14px; align-items: center; border-top: 1px solid #e1e5dc; padding: 11px 0; font-size: 12px; }
li strong,li small { display: block; } li small { margin-top: 5px; } a { color: #315f46; text-decoration: underline; white-space: nowrap; }
.empty { padding: 10px 0; }.message { margin: 0 0 12px; color: #315f46; }.error { color: #9c4c32; }
@media (max-width: 700px) { .channels { grid-template-columns: 1fr; }.section-heading { flex-direction: column; }.host-row { grid-template-columns: 1fr; } }
</style>
