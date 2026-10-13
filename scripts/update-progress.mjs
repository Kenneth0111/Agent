import { execFileSync } from 'node:child_process'
import { readFileSync, writeFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { dirname, join, resolve } from 'node:path'

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..')
const planPath = join(root, 'docs/superpowers/plans/2026-09-24-agent-daily-development-plan.md')
const reportPath = join(root, 'docs/PROGRESS.md')
const plan = readFileSync(planPath, 'utf8').replace(/\r\n/g, '\n')

function localStatus() {
  const git = (...args) => execFileSync('git', args, { cwd: root, encoding: 'utf8' }).trim()
  const changes = git('status', '--short')
  const upstream = git('rev-parse', '--abbrev-ref', '--symbolic-full-name', '@{upstream}')
  const [ahead, behind] = git('rev-list', '--left-right', '--count', `HEAD...${upstream}`).split(/\s+/).map(Number)
  console.log(changes ? `未提交文件：\n${changes}` : '未提交文件：0')
  console.log(`未推送提交：${ahead}；远程领先本地：${behind}`)
}

if (process.argv.includes('--status')) {
  localStatus()
  process.exit(0)
}

const tasks = [...plan.matchAll(/^- \[([ x])\] \*\*(W([1-8])-D[1-7])｜[^｜]+｜[^｜]+｜(.+?)\*\*(.*)$/gm)]
  .map(([, checked, id, week, title, suffix]) => ({
    id, week: Number(week), title,
    status: checked === 'x' ? 'done' : suffix.includes('<!-- progress:partial -->') ? 'partial' : 'todo',
    conflictingStatus: checked === 'x' && suffix.includes('<!-- progress:partial -->'),
  }))
if (tasks.length !== 56 || tasks.some(task => task.conflictingStatus))
  throw new Error('计划任务数量或部分完成标记有误，请检查 8 周清单。')

const count = (items, status) => items.filter(item => item.status === status).length
const bar = (done, total, width = 10) => {
  const filled = Math.round(done / total * width)
  return '█'.repeat(filled) + '░'.repeat(width - filled)
}
const done = count(tasks, 'done')
const partial = count(tasks, 'partial')
const todo = count(tasks, 'todo')
const lines = [
  '# 开发进度',
  '',
  '固定地址：本页。状态由[8 周每日清单](superpowers/plans/2026-09-24-agent-daily-development-plan.md)生成；完成表示该任务已验收并勾选，部分完成表示已有实现但尚未达到验收标准。',
  '',
  `**总进度：${done} / ${tasks.length} 个日任务（${Math.round(done / tasks.length * 100)}%）**  \`${bar(done, tasks.length, 20)}\``,
  '',
  `已完成 ${done} · 部分完成 ${partial} · 尚未开始 ${todo}。这是任务数量占比，不是工时或最终交付进度。`,
  '',
  '> GitHub 无法读取本机尚未提交或推送的改动。在项目根目录运行 `node scripts/update-progress.mjs --status`，可实时查看未提交文件和未推送提交。',
  '',
  '| 周次 | 完成 | 部分完成 | 尚未开始 | 进度 |',
  '| --- | ---: | ---: | ---: | --- |',
]

for (let week = 1; week <= 8; week++) {
  const items = tasks.filter(task => task.week === week)
  if (items.length !== 7) throw new Error(`第 ${week} 周不是 7 个任务。`)
  const heading = plan.match(new RegExp(`^### 第 ${week} 周：(.+)$`, 'm'))?.[1]
  if (!heading) throw new Error(`找不到第 ${week} 周标题。`)
  const weekDone = count(items, 'done')
  const weekPartial = count(items, 'partial')
  lines.push(`| 第 ${week} 周 · ${heading} | ${weekDone}/7 | ${weekPartial} | ${count(items, 'todo')} | \`${bar(weekDone, 7)}\` |`)
}

lines.push('', '## 每日任务', '')
for (let week = 1; week <= 8; week++) {
  const items = tasks.filter(task => task.week === week)
  lines.push(`<details${week === 4 || week === 5 ? ' open' : ''}>`, `<summary>第 ${week} 周 · 点击查看 7 项任务</summary>`, '', '| 任务 | 状态 | 内容 |', '| --- | --- | --- |')
  for (const task of items) {
    const label = { done: '✅ 已完成', partial: '🟠 部分完成', todo: '○ 尚未开始' }[task.status]
    lines.push(`| ${task.id} | ${label} | ${task.title} |`)
  }
  lines.push('', '</details>', '')
}
lines.push('任务细节与完成标准见[8 周每日清单](superpowers/plans/2026-09-24-agent-daily-development-plan.md)；验证记录见[每日开发记录](development-log.md)。', '')
const report = lines.join('\n')

if (process.argv.includes('--check')) {
  if (readFileSync(reportPath, 'utf8').replace(/\r\n/g, '\n') !== report) {
    console.error('docs/PROGRESS.md 已过期，请运行 node scripts/update-progress.mjs 并提交结果。')
    process.exit(1)
  }
  console.log('docs/PROGRESS.md 与任务清单一致。')
} else {
  writeFileSync(reportPath, report, 'utf8')
  console.log('已更新 docs/PROGRESS.md。')
}
