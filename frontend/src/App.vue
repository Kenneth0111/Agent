<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import LoginPage from './pages/LoginPage.vue'
import ChatPage from './pages/ChatPage.vue'

const connection = ref<'checking' | 'connected' | 'unavailable'>('checking')
const signedIn = ref(false)
const connectionLabel = computed(() => ({
  checking: '正在连接服务',
  connected: '服务已连接',
  unavailable: '暂时无法连接',
})[connection.value])
let controller: AbortController | undefined

async function checkConnection() {
  connection.value = 'checking'
  controller = new AbortController()
  const timer = setTimeout(() => controller?.abort(), 5000)
  try {
    const response = await fetch('/api/health', { signal: controller.signal })
    const body: unknown = await response.json()
    connection.value = response.ok && typeof body === 'object' && body !== null
      && 'status' in body && body.status === 'UP' ? 'connected' : 'unavailable'
  } catch {
    connection.value = 'unavailable'
  } finally {
    clearTimeout(timer)
  }
}

onMounted(checkConnection)
onBeforeUnmount(() => controller?.abort())
</script>

<template>
  <div class="workspace">
    <header class="masthead">
      <a class="brand" href="/" aria-label="创作工作台首页">
        <span class="brand-mark" aria-hidden="true">c.</span>
        <span>创作工作台<small>CREATOR AGENT</small></span>
      </a>
      <span class="edition">起步 · FIRST CHAPTER</span>
    </header>

    <main>
      <section class="intro" aria-labelledby="page-title">
        <p class="eyebrow"><span aria-hidden="true"></span> 为持续创作，留一点空间</p>
        <h1 id="page-title">把想法，变成<br /><em>下一条作品。</em></h1>
        <p class="intro-copy">从一个值得分享的问题开始。整理资料，写下表达，<br class="desktop-break" />让每一次准备，都成为下一次创作的起点。</p>
      </section>

      <LoginPage @auth-change="signedIn = $event" />
      <ChatPage v-if="signedIn" />

      <section class="preparation" aria-labelledby="preparation-title">
        <div class="preparation-copy">
          <span class="section-index">01 / 准备开始</span>
          <h2 id="preparation-title">你的创作空间正在搭建</h2>
          <p>目前已开放登录与服务连接检查。抖音账号、资料库和内容计划将在后续开发中逐步接入。</p>
        </div>
        <div class="connection-panel">
          <span class="connection" :class="connection" role="status" aria-live="polite">
            <span class="status-dot" aria-hidden="true"></span>{{ connectionLabel }}
          </span>
          <p>{{ connection === 'unavailable' ? '连接恢复后，可以重新检查。' : '这里显示最近一次服务连接检查结果。' }}</p>
          <button type="button" :disabled="connection === 'checking'" @click="checkConnection">
            {{ connection === 'checking' ? '检查中…' : '重新检查' }} <span aria-hidden="true">↗</span>
          </button>
        </div>
      </section>

      <section class="path" aria-label="后续开发方向">
        <article><span>01</span><h3>资料，成为依据</h3><p>留住值得引用的内容，给每个想法一个出处。</p></article>
        <article><span>02</span><h3>想法，成为脚本</h3><p>把零散的知识，组织成适合讲述的表达。</p></article>
        <article><span>03</span><h3>创作，成为日常</h3><p>安排自己的节奏，再从真实反馈中继续调整。</p></article>
      </section>
    </main>

    <footer><span>先认真准备一条，再开始下一条。</span><span>CREATOR AGENT · 0.1</span></footer>
  </div>
</template>

<style>
:root { font-family: 'Microsoft YaHei', 'PingFang SC', sans-serif; color: #263b32; background: #f4f2ec; font-synthesis: none; }
* { box-sizing: border-box; }
body { margin: 0; }
button, a { -webkit-tap-highlight-color: transparent; }
a { color: inherit; text-decoration: none; }
button { font: inherit; }
.workspace { max-width: 1264px; padding: 0 64px; margin: auto; }
.masthead { min-height: 110px; display: flex; align-items: center; justify-content: space-between; border-bottom: 1px solid #d8ddd3; }
.brand { display: flex; align-items: center; gap: 13px; font-size: 16px; font-weight: 700; letter-spacing: 1px; }
.brand-mark { display: grid; place-items: center; width: 44px; height: 44px; background: #234d3b; color: #f4f2ec; border-radius: 50%; font: italic 34px Georgia, serif; padding-bottom: 6px; }
.brand small { display: block; font: 10px/2.1 Consolas, monospace; letter-spacing: 2px; color: #6b796c; }
.edition, .section-index { font: 11px Consolas, 'Microsoft YaHei', monospace; letter-spacing: 1.5px; color: #617363; }
.intro { padding: 65px 0 49px; }
.eyebrow { display: flex; align-items: center; gap: 10px; font-size: 12px; color: #617363; letter-spacing: 2px; }
.eyebrow span { width: 7px; height: 7px; background: #849b3c; border-radius: 50%; }
h1 { font-family: 'STSong', 'SimSun', serif; font-size: clamp(40px, 5.4vw, 68px); font-weight: 500; letter-spacing: 1px; line-height: 1.35; margin: 24px 0; }
h1 em { font-style: normal; color: #718540; }
.intro-copy { font-size: 14px; line-height: 1.95; color: #627064; }
.preparation { display: grid; grid-template-columns: 1fr 300px; border: 1px solid #d5dcd0; border-radius: 4px; background: #fbfaf6; }
.preparation-copy { padding: 32px 34px; }
h2 { font-size: 21px; font-weight: 500; margin: 18px 0 12px; }
.preparation p, .path p { font-size: 12px; line-height: 1.9; color: #637166; margin: 0; }
.connection-panel { border-left: 1px solid #d5dcd0; padding: 31px; display: flex; align-items: flex-start; flex-direction: column; justify-content: center; gap: 11px; }
.connection { display: inline-flex; align-items: center; gap: 9px; font-size: 12px; }
.status-dot { width: 7px; height: 7px; border-radius: 50%; background: #999570; }
.connected .status-dot { background: #517d43; }
.unavailable .status-dot { background: #b35f3d; }
.connection-panel button { background: none; border: 0; padding: 8px 0; font-size: 12px; color: #234d3b; cursor: pointer; border-bottom: 1px solid #9aa88d; }
button span { margin-left: 30px; }
button:disabled { opacity: .55; cursor: wait; }
button:focus-visible, a:focus-visible { outline: 2px solid #577c39; outline-offset: 5px; }
.path { display: grid; grid-template-columns: repeat(3, 1fr); gap: 36px; margin: 42px 0 52px; }
.path article { border-top: 1px solid #d5dcd0; padding-top: 21px; }
.path article > span { font: 12px Consolas, monospace; color: #7b8b65; }
.path h3 { font-size: 15px; font-weight: 500; margin: 13px 0 8px; }
footer { border-top: 1px solid #d5dcd0; padding: 22px 0; display: flex; justify-content: space-between; gap: 16px; font-size: 10px; color: #74806e; }
footer span:last-child { font-family: Consolas, monospace; letter-spacing: 1px; }
@media (max-width: 700px) {
  .workspace { padding: 0 24px; }
  .masthead { min-height: 88px; }
  .edition { display: none; }
  .intro { padding: 40px 0 30px; }
  .intro-copy { font-size: 13px; }
  .desktop-break { display: none; }
  .preparation { grid-template-columns: 1fr; }
  .preparation-copy, .connection-panel { padding: 24px; }
  .connection-panel { border-left: 0; border-top: 1px solid #d5dcd0; }
  .path { grid-template-columns: 1fr; gap: 22px; margin: 30px 0; }
  .path article { padding-top: 17px; }
  footer { flex-wrap: wrap; }
}
</style>
