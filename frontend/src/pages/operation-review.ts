export interface ReviewRequest { accountId: string; topic: string; from: string; to: string; requestId: string }
interface Point { capturedAt: string; previousCapturedAt: string | null; views: string | null; likes: string | null;
  commentCount: string | null; viewsDelta: string | null; likesDelta: string | null; commentCountDelta: string | null;
  likeViewPercent: string | null; commentViewPercent: string | null; correction: boolean }
export interface Report {
  id: string; accountId: string; status: string; request: ReviewRequest; attempts: number; errorCode: string | null; createdAt: string
  facts: null | {
    account: { name: string; audience: string; positioning: string; columns: string[]; weeklyTarget: number }
    from: string; to: string; dataUpdatedAt: string | null; limitations: string[]
    metrics: { citationId: string; workId: string; videoId: string; source: string; observations: number;
      truncated: boolean; firstCapturedAt: string; previous: Point | null; latest: Point }[]
    evidence: { accountId: string; topic: string; commentStatus: string; commentBasis: string; commentsTruncated: boolean
      comments: { citationId: string; workId: string; videoId: string; text: string; publishedAt: string | null; capturedAt: string; source: string }[]
      referenceMaterials: { status: string; results: { materialId: string; title: string; snippet: string;
        sourceUrl: string | null; fileName: string | null; kind: string }[] } }
    publications: { workId: string; planId: string; itemId: string; topicId: string; scriptId: string;
      publicationUrl: string | null; publishedAt: string; scheduledDate: string }[]
  }
  advice: null | { hypotheses: { possibleExplanation: string; evidenceIds: string[] }[];
    experiments: { proposedTopic: string; action: string; measurement: string; evidenceIds: string[] }[] }
}
const statuses: Record<string, string> = { RUNNING: '正在采集事实', GENERATING: '正在生成建议', SUCCEEDED: '复盘已保存',
  INSUFFICIENT_DATA: '样本不足，已跳过模型', FAILED: '复盘未完成', NEEDS_REVIEW: '结果需人工核对' }
export function statusLabel(status: string) { return statuses[status] ?? status }
export function object(value: unknown): value is Record<string, unknown> { return typeof value === 'object' && value !== null }
function strings(value: unknown): value is string[] { return Array.isArray(value) && value.every(item => typeof item === 'string') }
function nullableString(value: unknown) { return value === null || typeof value === 'string' }
function time(value: unknown): value is string { return typeof value === 'string' && Number.isFinite(Date.parse(value)) }
function keys(value: Record<string, unknown>, names: string[]) { return names.every(key => typeof value[key] === 'string') }
function items(value: unknown, check: (item: Record<string, unknown>) => boolean) {
  return Array.isArray(value) && value.every(item => object(item) && check(item))
}
export function validRequest(value: unknown, accountId: string): value is ReviewRequest {
  return object(value) && value.accountId === accountId && typeof value.topic === 'string'
    && value.topic.trim().length > 0 && value.topic.length <= 100 && typeof value.requestId === 'string'
    && time(value.from) && time(value.to) && Date.parse(value.to) > Date.parse(value.from)
    && Date.parse(value.to) - Date.parse(value.from) <= 31 * 86400000
}
function point(value: unknown) {
  return object(value) && time(value.capturedAt) && (value.previousCapturedAt === null || time(value.previousCapturedAt))
    && typeof value.correction === 'boolean'
    && ['views', 'likes', 'commentCount'].every(key => value[key] === null || typeof value[key] === 'string' && /^\d+$/.test(value[key] as string))
    && ['viewsDelta', 'likesDelta', 'commentCountDelta'].every(key => value[key] === null || typeof value[key] === 'string' && /^-?\d+$/.test(value[key] as string))
    && ['likeViewPercent', 'commentViewPercent'].every(key => nullableString(value[key]))
}
export function validReport(value: unknown, accountId: string): value is Report {
  if (!object(value) || !keys(value, ['id', 'status']) || !Object.hasOwn(statuses, String(value.status))
    || value.accountId !== accountId || !validRequest(value.request, accountId) || !time(value.createdAt)
    || !Number.isInteger(value.attempts) || !nullableString(value.errorCode)) return false
  if (['SUCCEEDED', 'INSUFFICIENT_DATA'].includes(String(value.status)) && (value.facts === null || value.advice === null)) return false
  if (value.facts !== null) {
    const facts = value.facts
    if (!object(facts) || !time(facts.from) || !time(facts.to) || facts.from !== value.request.from || facts.to !== value.request.to
      || !(facts.dataUpdatedAt === null || time(facts.dataUpdatedAt)) || !strings(facts.limitations)
      || !object(facts.account) || !keys(facts.account, ['name', 'audience', 'positioning']) || !strings(facts.account.columns)
      || !Number.isInteger(facts.account.weeklyTarget)
      || !items(facts.metrics, row => keys(row, ['citationId', 'workId', 'videoId', 'source'])
        && Number.isInteger(row.observations) && typeof row.truncated === 'boolean' && time(row.firstCapturedAt)
        && (row.previous === null || point(row.previous)) && point(row.latest))) return false
    const evidence = facts.evidence
    if (!object(evidence) || evidence.accountId !== accountId || !keys(evidence, ['topic', 'commentStatus', 'commentBasis'])
      || typeof evidence.commentsTruncated !== 'boolean'
      || !items(evidence.comments, row => keys(row, ['citationId', 'workId', 'videoId', 'text', 'source'])
        && time(row.capturedAt) && (row.publishedAt === null || time(row.publishedAt)))
      || !object(evidence.referenceMaterials) || typeof evidence.referenceMaterials.status !== 'string'
      || !items(evidence.referenceMaterials.results, row => keys(row, ['materialId', 'title', 'snippet', 'kind'])
        && nullableString(row.sourceUrl) && nullableString(row.fileName))
      || !items(facts.publications, row => keys(row, ['workId', 'planId', 'itemId', 'topicId', 'scriptId', 'scheduledDate'])
        && nullableString(row.publicationUrl) && time(row.publishedAt))) return false
  }
  return value.advice === null || object(value.advice)
    && items(value.advice.hypotheses, row => typeof row.possibleExplanation === 'string' && strings(row.evidenceIds))
    && items(value.advice.experiments, row => keys(row, ['proposedTopic', 'action', 'measurement']) && strings(row.evidenceIds))
}
