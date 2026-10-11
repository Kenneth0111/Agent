import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import AnalyticsPage from './AnalyticsPage.vue'
import { request } from '../api/http'

vi.mock('../api/http', async importOriginal => ({ ...await importOriginal<typeof import('../api/http')>(),
  request: vi.fn() }))

const accounts = [{ id: 'a', name: 'Java 账号' }, { id: 'b', name: '英语账号' }]
const works = [{ id: 'work', videoId: '0000123', sources: ['MANUAL', 'OTHER'] }]
const point = { capturedAt: '2026-10-10T10:00:00Z', previousCapturedAt: null,
  views: '0', likes: null, commentCount: '0', viewsDelta: null, likesDelta: null,
  commentCountDelta: null, likeViewPercent: null, commentViewPercent: null, correction: false }
const trend = { workId: 'work', source: 'MANUAL', truncated: false, points: [point] }

beforeEach(() => {
  vi.mocked(request).mockReset()
  vi.mocked(request).mockImplementation(async path => path === '/api/accounts' ? accounts
    : path.startsWith('/api/metrics/works?') ? works : trend)
})

describe('video observations', () => {
  it('distinguishes unknown from zero and never invents a first observation delta', async () => {
    const wrapper = mount(AnalyticsPage, { global: { stubs: { OperationReviewPanel: true } } })
    await flushPromises()
    expect(wrapper.get('#metrics-account').element).toBeDefined()
    expect(wrapper.get('#metrics-work').text()).toContain('0000123')
    expect(wrapper.get('.metric-summary').text()).toContain('未知')
    expect(wrapper.get('.metric-summary').text()).toContain('0')
    expect(wrapper.get('tbody').text()).toContain('首次观测')
    expect(wrapper.get('tbody').text()).toContain('不可计算')
    expect(wrapper.get('tbody').text()).not.toContain('+0')
    wrapper.unmount()
  })

  it('renders exact large counts and warns about decreases and limited windows', async () => {
    vi.mocked(request).mockImplementation(async path => path === '/api/accounts' ? accounts
      : path.startsWith('/api/metrics/works?') ? works : { ...trend, truncated: true, points: [point,
        { ...point, previousCapturedAt: '2026-10-10T09:00:00Z', views: '9223372036854775807',
          likes: '1', viewsDelta: '-10', likesDelta: '0', likeViewPercent: '0.000000', correction: true },
      ] })
    const wrapper = mount(AnalyticsPage, { global: { stubs: { OperationReviewPanel: true } } })
    await flushPromises()
    expect(wrapper.get('.metric-summary').text()).toContain('9223372036854775807')
    expect(wrapper.get('tbody').text()).toContain('-10')
    expect(wrapper.text()).toContain('当前显示最近 1000 条观测')
    expect(wrapper.text()).toContain('累计值下降')
    expect(wrapper.get('tbody').text()).toContain('窗口首条，变化未知')
    expect(wrapper.get('tbody').text()).not.toContain('首次观测')
    wrapper.unmount()
  })

  it('clears old observations and ignores a late source response after switching accounts', async () => {
    let resolveOld: (value: unknown) => void = () => {}
    const delayed = new Promise(resolve => { resolveOld = resolve })
    vi.mocked(request).mockImplementation(async path => path === '/api/accounts' ? accounts
      : path === '/api/metrics/works?accountId=b' ? []
        : path.startsWith('/api/metrics/works?') ? works
          : path.endsWith('source=OTHER') ? delayed : trend)
    const wrapper = mount(AnalyticsPage, { global: { stubs: { OperationReviewPanel: true } } })
    await flushPromises()
    await wrapper.get('#metrics-source').setValue('OTHER')
    await wrapper.get('#metrics-account').setValue('b')
    await flushPromises()
    expect(wrapper.find('.metric-summary').exists()).toBe(false)
    expect(wrapper.text()).toContain('这个账号还没有导入作品数据')
    resolveOld({ ...trend, source: 'OTHER' })
    await flushPromises()
    expect(wrapper.find('.metric-summary').exists()).toBe(false)
    wrapper.unmount()
  })

  it('shows read failures separately from an empty account and allows refresh', async () => {
    const wrapper = mount(AnalyticsPage, { global: { stubs: { OperationReviewPanel: true } } })
    await flushPromises()
    vi.mocked(request).mockRejectedValueOnce(new Error('offline'))
    await wrapper.get('button').trigger('click')
    await flushPromises()
    expect(wrapper.get('[role="alert"]').text()).toContain('无法读取作品')
    expect(wrapper.text()).not.toContain('这个账号还没有导入作品数据')
    await wrapper.get('button').trigger('click')
    await flushPromises()
    expect(wrapper.find('[role="alert"]').exists()).toBe(false)
    expect(wrapper.find('.metric-summary').exists()).toBe(true)
    wrapper.unmount()
  })

  it('rejects a trend response for a different source instead of displaying it', async () => {
    vi.mocked(request).mockImplementation(async path => path === '/api/accounts' ? accounts
      : path.startsWith('/api/metrics/works?') ? works : { ...trend, source: 'FOREIGN' })
    const wrapper = mount(AnalyticsPage, { global: { stubs: { OperationReviewPanel: true } } })
    await flushPromises()
    expect(wrapper.get('[role="alert"]').text()).toContain('无法读取观测记录')
    expect(wrapper.find('.metric-summary').exists()).toBe(false)
    wrapper.unmount()
  })
})
