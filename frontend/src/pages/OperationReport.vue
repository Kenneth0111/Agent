<script setup lang="ts">
import { computed } from 'vue'
import { statusLabel, type Report } from './operation-review'
const props = defineProps<{ report: Report }>()
const facts = computed(() => props.report.facts)
const columns = [ { key: 'views', delta: 'viewsDelta', label: '播放' }, { key: 'likes', delta: 'likesDelta', label: '点赞' },
  { key: 'commentCount', delta: 'commentCountDelta', label: '评论量' } ] as const
function stamp(value: string | null) { return value === null ? '未知' : new Date(value).toLocaleString('zh-CN', { hour12: false }) }
function anchor(citationId: string) { return `review-${props.report.id}-${citationId.replace(/[^a-zA-Z0-9_-]/g, '-')}` }
function url(value: string | null) {
  try { const parsed = new URL(value ?? ''); return ['https:', 'http:'].includes(parsed.protocol) ? parsed.href : null } catch { return null }
}
</script>

<template>
  <article class="report" aria-label="运营复盘报告">
    <div class="report-heading"><h4>{{ report.request.topic }} · {{ statusLabel(report.status) }}</h4><time :datetime="report.createdAt">{{ stamp(report.createdAt) }}</time></div>
    <p>范围：{{ stamp(report.request.from) }} 至 {{ stamp(report.request.to) }}（结束时刻不包含，本地时区）</p>
    <p v-if="['RUNNING', 'GENERATING', 'NEEDS_REVIEW'].includes(report.status)" class="warning">请刷新读取保存状态，不会自动重发模型请求。服务中断或超时可能需要核对用量；创建另一份复盘可能产生新的费用。</p>
    <p v-if="report.errorCode" class="error">错误码：{{ report.errorCode }}。请核对配置、预算或范围后再决定是否创建新复盘。</p>
    <template v-if="facts">
      <h4>已观测事实</h4>
      <p :id="anchor('account')">[account] {{ facts.account.name }} · {{ facts.account.positioning }} · 面向 {{ facts.account.audience }}</p>
      <p>引用数据更新至：{{ stamp(facts.dataUpdatedAt) }}。这份报告保留生成时的事实，新导入数据不会改写旧报告。</p>
      <p v-if="!facts.metrics.length">这个窗口没有指标依据。</p>
      <div v-for="metric in facts.metrics" :id="anchor(metric.citationId)" :key="metric.citationId" class="evidence">
        <h5>[{{ metric.citationId }}] 作品 {{ metric.videoId }} · {{ metric.source }}</h5>
        <p>{{ metric.observations }} 条观测{{ metric.truncated ? '（仅最近 1000 条）' : '' }} · 窗口首条 {{ stamp(metric.firstCapturedAt) }}</p>
        <div class="table-wrap" tabindex="0" aria-label="复盘指标，可横向滚动"><table>
          <thead><tr><th>指标</th><th>前一观测 {{ stamp(metric.previous?.capturedAt ?? null) }}</th><th>最近观测 {{ stamp(metric.latest.capturedAt) }}</th><th>相邻变化</th></tr></thead>
          <tbody><tr v-for="column in columns" :key="column.key"><th>{{ column.label }}</th><td>{{ metric.previous?.[column.key] ?? '未知' }}</td>
            <td>{{ metric.latest[column.key] ?? '未知' }}</td><td>{{ metric.latest[column.delta] ?? '未知' }}</td></tr></tbody>
        </table></div>
        <p>累计点赞 / 播放：{{ metric.latest.likeViewPercent === null ? '不可计算' : metric.latest.likeViewPercent + '%' }}；累计评论 / 播放：{{ metric.latest.commentViewPercent === null ? '不可计算' : metric.latest.commentViewPercent + '%' }}。相邻变化不代表整个窗口新增量。</p>
      </div>
      <h4>评论样本依据</h4><p>{{ facts.evidence.commentBasis }}</p>
      <p v-if="facts.evidence.commentsTruncated">仅展示最近 20 条匹配样本，不能代表全部观众。</p>
      <blockquote v-for="comment in facts.evidence.comments" :id="anchor(comment.citationId)" :key="comment.citationId">
        <p>{{ comment.text }}</p><footer>[{{ comment.citationId }}] · 作品 {{ comment.videoId }} · {{ comment.source }} · 采集 {{ stamp(comment.capturedAt) }} · 发布时间 {{ stamp(comment.publishedAt) }}</footer>
      </blockquote>
      <h4>参考资料</h4><p v-if="!facts.evidence.referenceMaterials.results.length">未找到可引用的本人资料。</p>
      <div v-for="material in facts.evidence.referenceMaterials.results" :id="anchor('material:' + material.materialId)" :key="material.materialId" class="evidence">
        <h5>[material:{{ material.materialId }}] {{ material.title }}</h5><p>{{ material.snippet }}</p>
        <a v-if="url(material.sourceUrl)" :href="url(material.sourceUrl)!" target="_blank" rel="noopener noreferrer">查看原始来源 ↗</a><span v-else>{{ material.fileName ?? '未提供链接或文件名' }}</span>
      </div>
      <h4>原发布计划关联</h4><p v-if="!facts.publications.length">未找到可核对的原发布计划，不能猜测关联。</p>
      <div v-for="publication in facts.publications" :key="publication.itemId" class="evidence">
        <p>计划 {{ publication.planId }} · 项目 {{ publication.itemId }} · 站内登记 {{ stamp(publication.publishedAt) }}</p>
        <p>选题 {{ publication.topicId }} · 脚本 {{ publication.scriptId }} · 排期 {{ publication.scheduledDate }}</p>
        <a v-if="url(publication.publicationUrl)" :href="url(publication.publicationUrl)!" target="_blank" rel="noopener noreferrer">查看已登记作品 ↗</a>
      </div>
      <div class="limitations"><h4>样本与口径限制</h4><ul><li v-for="(limitation, index) in facts.limitations" :key="index">{{ limitation }}</li></ul></div>
    </template>
    <template v-if="report.advice">
      <h4>可能解释 · 尚未证实</h4><p v-if="!report.advice.hypotheses.length">暂无可供核对的解释。</p>
      <div v-for="(hypothesis, index) in report.advice.hypotheses" :key="index" class="advice"><p>{{ hypothesis.possibleExplanation }}</p>
        <a v-for="id in hypothesis.evidenceIds" :key="id" :href="'#' + anchor(id)">[{{ id }}]</a></div>
      <h4>下一轮选题实验 · 尚未执行</h4><p v-if="!report.advice.experiments.length">补充指标或主题匹配的评论样本后，再创建复盘。</p>
      <div v-for="(experiment, index) in report.advice.experiments" :key="index" class="advice"><h5>{{ index + 1 }} / {{ experiment.proposedTopic }}</h5>
        <p>拍摄动作：{{ experiment.action }}</p><p>观察方法：{{ experiment.measurement }}</p>
        <a v-for="id in experiment.evidenceIds" :key="id" :href="'#' + anchor(id)">[{{ id }}]</a></div>
    </template>
    <details><summary>报告追踪信息</summary><p>报告 {{ report.id }} · 请求 {{ report.request.requestId }} · 流程尝试 {{ report.attempts }} 次。实际调用及费用以用量账本为准。</p></details>
  </article>
</template>

<style scoped>
.report { margin-top: 24px; padding: 24px; border: 1px solid #d5dcd0; background: #f6f5ef; overflow-wrap: anywhere; }
.report-heading { display: flex; flex-wrap: wrap; gap: 12px; justify-content: space-between; align-items: baseline; }
h4 { font-size: 14px; font-weight: 500; color: #234d3b; margin: 24px 0 12px; } .report-heading h4 { margin-top: 0; }
h5 { font-size: 13px; font-weight: 500; margin: 14px 0 10px; }
p, li, footer, time, span, summary { font-size: 12px; line-height: 1.9; color: #637166; }
.evidence { border-top: 1px solid #d5dcd0; padding: 6px 0; scroll-margin-top: 24px; }
.table-wrap { overflow-x: auto; } table { width: 100%; border-collapse: collapse; font-size: 12px; text-align: left; font-variant-numeric: tabular-nums; }
th, td { padding: 10px; border-bottom: 1px solid #d5dcd0; white-space: nowrap; } th { font-weight: 500; }
blockquote { margin: 12px 0; padding: 0 16px; border-left: 2px solid #8da582; white-space: pre-wrap; }
.limitations, .warning { padding: 12px 16px; background: #f1ecd9; } .limitations h4 { margin-top: 0; } ul { padding-left: 18px; }
.advice { padding: 4px 0 12px 16px; border-left: 2px solid #8da582; margin: 12px 0; }
a { font-size: 12px; color: #234d3b; margin-right: 12px; text-underline-offset: 4px; }
.error { color: #a13d2d; } details { border-top: 1px solid #d5dcd0; padding-top: 16px; margin-top: 20px; } summary { cursor: pointer; }
:focus-visible { outline: 2px solid #577c39; outline-offset: 3px; }
@media (max-width: 760px) { .report { padding: 16px; } }
</style>
