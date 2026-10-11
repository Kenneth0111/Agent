import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import OperationReviewPanel from './OperationReviewPanel.vue'
import { postJsonWithCsrf, request } from '../api/http'
import type { ReviewRequest } from './operation-review'

vi.mock('../api/http', async importOriginal => ({ ...await importOriginal<typeof import('../api/http')>(), request: vi.fn(), postJsonWithCsrf: vi.fn() }))
const report = { id: 'r1', accountId: 'a', status: 'INSUFFICIENT_DATA', attempts: 0, errorCode: null,
  createdAt: '2026-10-11T10:00:00Z', request: { accountId: 'a', topic: '线程池', from: '2026-10-04T00:00:00Z', to: '2026-10-11T00:00:00Z', requestId: 'key' },
  facts: { account: { name: 'Java 账号', audience: '程序员', positioning: 'Java', columns: ['Java 面试'], weeklyTarget: 3 },
    from: '2026-10-04T00:00:00Z', to: '2026-10-11T00:00:00Z', dataUpdatedAt: null,
    metrics: [], publications: [], limitations: ['缺少指标依据'],
    evidence: { accountId: 'a', topic: '线程池', commentStatus: 'NO_COMMENT_SAMPLES', commentBasis: '尚未导入评论样本，缺少评论依据。',
      commentsTruncated: false, comments: [], referenceMaterials: { status: 'INSUFFICIENT_MATERIAL', results: [] } },
  }, advice: { hypotheses: [], experiments: [] },
}
function reply(input: unknown) {
  const request = input as ReviewRequest
  return { ...report, request, facts: { ...report.facts, from: request.from, to: request.to } }
}
beforeEach(() => { sessionStorage.clear(); vi.mocked(request).mockReset().mockResolvedValue([]); vi.mocked(postJsonWithCsrf).mockReset() })
afterEach(() => vi.restoreAllMocks())

it('loads saved reports without model calls and generates an explicit cold-start report', async () => {
  vi.mocked(postJsonWithCsrf).mockImplementation(async (_path, input) => reply(input))
  const wrapper = mount(OperationReviewPanel, { props: { accountId: 'a' } })
  await flushPromises()
  expect(postJsonWithCsrf).not.toHaveBeenCalled()
  expect(wrapper.text()).toContain('尚无复盘报告')
  await wrapper.get('[aria-label=复盘关键词]').setValue('线程池')
  await wrapper.get('form').trigger('submit'); await flushPromises()
  expect(vi.mocked(postJsonWithCsrf).mock.calls[0]?.[0]).toBe('/api/metrics/reviews')
  expect(wrapper.text()).toContain('样本不足，已跳过模型')
  expect(wrapper.text()).toContain('缺少指标依据')
  wrapper.unmount()
})

it('reuses the request after a lost response and a page remount instead of issuing a new intent', async () => {
  vi.mocked(postJsonWithCsrf).mockRejectedValueOnce(new DOMException('Aborted', 'AbortError'))
    .mockImplementationOnce(async (_path, input) => reply(input))
  let wrapper = mount(OperationReviewPanel, { props: { accountId: 'a' } })
  await flushPromises(); await wrapper.get('[aria-label=复盘关键词]').setValue('线程池')
  await wrapper.get('form').trigger('submit'); await flushPromises()
  const first = vi.mocked(postJsonWithCsrf).mock.calls[0]?.[1]
  expect(wrapper.get('fieldset').attributes('disabled')).toBeDefined()
  expect(wrapper.text()).toContain('复用本次请求查询结果')
  wrapper.unmount()
  wrapper = mount(OperationReviewPanel, { props: { accountId: 'a' } })
  await flushPromises(); await wrapper.get('form').trigger('submit'); await flushPromises()
  expect(vi.mocked(postJsonWithCsrf).mock.calls[1]?.[1]).toEqual(first)
  expect(wrapper.text()).toContain('样本不足，已跳过模型')
  expect(sessionStorage.getItem('creator-review-request:a')).toBeNull()
  await wrapper.get('.review-heading button').trigger('click'); await flushPromises()
  expect(postJsonWithCsrf).toHaveBeenCalledTimes(2)
  wrapper.unmount()
})

it('does not send a paid request when the browser cannot persist its retry identifier', async () => {
  const write = vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => { throw new DOMException('Storage blocked', 'SecurityError') })
  const wrapper = mount(OperationReviewPanel, { props: { accountId: 'a' } })
  await flushPromises(); await wrapper.get('[aria-label=复盘关键词]').setValue('线程池')
  await wrapper.get('form').trigger('submit'); await flushPromises()
  expect(postJsonWithCsrf).not.toHaveBeenCalled()
  expect(wrapper.get('[role=alert]').text()).toContain('无法保存请求编号')
  wrapper.unmount(); write.mockRestore()
})

it('keeps the original request when a successful response omits its saved content', async () => {
  vi.mocked(postJsonWithCsrf).mockImplementationOnce(async (_path, input) => ({ ...reply(input), status: 'SUCCEEDED', facts: null, advice: null }))
  const wrapper = mount(OperationReviewPanel, { props: { accountId: 'a' } })
  await flushPromises(); await wrapper.get('[aria-label=复盘关键词]').setValue('线程池')
  await wrapper.get('form').trigger('submit'); await flushPromises()
  expect(wrapper.find('[aria-label=运营复盘报告]').exists()).toBe(false)
  expect(wrapper.get('fieldset').attributes('disabled')).toBeDefined()
  expect(sessionStorage.getItem('creator-review-request:a')).not.toBeNull()
  wrapper.unmount()
})

it('keeps an in-progress request locked and only reuses its identifier for status retrieval', async () => {
  vi.mocked(postJsonWithCsrf).mockImplementationOnce(async (_path, input) => ({ ...reply(input), status: 'GENERATING' }))
    .mockImplementationOnce(async (_path, input) => reply(input))
  const wrapper = mount(OperationReviewPanel, { props: { accountId: 'a' } })
  await flushPromises(); await wrapper.get('[aria-label=复盘关键词]').setValue('线程池')
  await wrapper.get('form').trigger('submit'); await flushPromises()
  expect(wrapper.get('fieldset').attributes('disabled')).toBeDefined()
  const first = vi.mocked(postJsonWithCsrf).mock.calls[0]?.[1]
  await wrapper.get('form').trigger('submit'); await flushPromises()
  expect(vi.mocked(postJsonWithCsrf).mock.calls[1]?.[1]).toEqual(first)
  wrapper.unmount()
})

it('prevents a double submit and ignores a report arriving after switching accounts', async () => {
  let resolveOld: (value: unknown) => void = () => {}
  vi.mocked(postJsonWithCsrf).mockReturnValueOnce(new Promise(resolve => { resolveOld = resolve }))
  const wrapper = mount(OperationReviewPanel, { props: { accountId: 'a' } })
  await flushPromises(); await wrapper.get('[aria-label=复盘关键词]').setValue('线程池')
  await wrapper.get('form').trigger('submit'); await wrapper.get('form').trigger('submit')
  expect(postJsonWithCsrf).toHaveBeenCalledTimes(1)
  await wrapper.setProps({ accountId: 'b' }); await flushPromises()
  resolveOld(reply(vi.mocked(postJsonWithCsrf).mock.calls[0]?.[1])); await flushPromises()
  expect(wrapper.find('[aria-label=运营复盘报告]').exists()).toBe(false)
  expect(wrapper.text()).toContain('尚无复盘报告')
  wrapper.unmount()
})

it('rejects a foreign account response and validates time windows before calling the model', async () => {
  vi.mocked(request).mockResolvedValueOnce([{ ...report, accountId: 'b' }])
  const wrapper = mount(OperationReviewPanel, { props: { accountId: 'a' } })
  await flushPromises()
  expect(wrapper.find('[aria-label=运营复盘报告]').exists()).toBe(false)
  expect(wrapper.get('[role=alert]').text()).toContain('无法读取报告')
  await wrapper.get('[aria-label=复盘关键词]').setValue('线程池')
  await wrapper.get('[aria-label=复盘起始时间]').setValue('2026-08-01T10:00')
  await wrapper.get('[aria-label=复盘结束时间]').setValue('2026-10-11T10:00')
  await wrapper.get('form').trigger('submit'); await flushPromises()
  expect(wrapper.get('[role=alert]').text()).toContain('最长 31 天')
  expect(postJsonWithCsrf).not.toHaveBeenCalled()
  wrapper.unmount()
})

it('renders facts, exact counts and traceable unverified advice while escaping samples and unsafe URLs', async () => {
  const point = { capturedAt: '2026-10-10T10:00:00Z', previousCapturedAt: null, views: '9223372036854775807', likes: null,
    commentCount: '0', viewsDelta: null, likesDelta: null, commentCountDelta: null, likeViewPercent: null, commentViewPercent: null, correction: false }
  vi.mocked(request).mockResolvedValueOnce([{ ...report, status: 'SUCCEEDED', attempts: 1, facts: { ...report.facts,
    metrics: [{ citationId: 'metric:1', workId: 'w', videoId: '0000123', source: 'DEMO_QA', observations: 1, truncated: false,
      firstCapturedAt: point.capturedAt, previous: null, latest: point }],
    evidence: { ...report.facts.evidence, commentStatus: 'MATCHED', comments: [{ citationId: 'comment:c1', workId: 'w', videoId: '0000123',
      text: '<script>bad()</script> 线程池 [邮箱]', publishedAt: null, capturedAt: point.capturedAt, source: 'DEMO_QA' }],
      referenceMaterials: { status: 'MATCHED', results: [{ materialId: 'm1', title: '线程池', snippet: '资料', sourceUrl: 'javascript:alert(1)', fileName: null, kind: 'TEXT' }] } },
  }, advice: { hypotheses: [{ possibleExplanation: '可能与题目相关，尚待验证', evidenceIds: ['metric:1'] }],
    experiments: [{ proposedTopic: '线程池饱和怎么办', action: '拍一条快问快答', measurement: '对比相同观察时长', evidenceIds: ['account', 'comment:c1', 'material:m1'] }] } }])
  const wrapper = mount(OperationReviewPanel, { props: { accountId: 'a' } })
  await flushPromises()
  expect(wrapper.text()).toContain('9223372036854775807')
  expect(wrapper.text()).toContain('未知')
  expect(wrapper.text()).toContain('尚未证实')
  expect(wrapper.text()).toContain('尚未执行')
  expect(wrapper.text()).toContain('<script>bad()</script>')
  expect(wrapper.find('script').exists()).toBe(false)
  expect(wrapper.find('a[href^="javascript:"]').exists()).toBe(false)
  for (const link of wrapper.findAll('a[href^="#review-"]')) {
    expect(wrapper.find(link.attributes('href')!).exists()).toBe(true)
  }
  expect(postJsonWithCsrf).not.toHaveBeenCalled()
  wrapper.unmount()
})
