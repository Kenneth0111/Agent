import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import OperationsPage from './OperationsPage.vue'
import { postJsonWithCsrf, putJsonWithCsrf, request } from '../api/http'

vi.mock('../api/http', () => ({ request: vi.fn(), postJsonWithCsrf: vi.fn(), putJsonWithCsrf: vi.fn() }))

const job = { id: 'job-1', accountId: 'account-1', dayOfWeek: 1, localTime: '09:00:00',
  timeZone: 'Asia/Shanghai', enabled: true, instruction: '每周三条', slots: [
    { column: 'Java 面试', materialIds: ['material-1'], instruction: '' },
    { column: 'Java 面试', materialIds: ['material-1'], instruction: '' },
    { column: '英语跟读', materialIds: ['material-1'], instruction: '' },
  ] }
let paused = true

beforeEach(() => {
  paused = true
  vi.mocked(request).mockReset()
  vi.mocked(postJsonWithCsrf).mockReset()
  vi.mocked(putJsonWithCsrf).mockReset()
  vi.mocked(request).mockImplementation(async path => {
    if (path === '/api/accounts') return [{ id: 'account-1', name: '技术号' }]
    if (path === '/api/materials') return [{ id: 'material-1', title: '面试笔记', accountIds: [] }]
    if (path === '/api/usage/summary') return { calls: 2, succeeded: 1, failed: 1,
      pendingReconciliation: 1, knownCostCny: 0 }
    if (path === '/api/usage/budget') return { enabled: true, utcMonth: '2026-10',
      userCalls: 2, userCallLimit: 100, systemLimitCny: 100,
      pauseCode: paused ? 'BUDGET_LIMIT_REACHED' : null }
    if (path === '/api/usage/calls') return [
      { id: 'call-1', taskId: 'task-1', kind: 'MODEL', provider: 'deepseek-chat',
        status: 'SUCCEEDED', costCny: 0, reservedCny: null, errorCode: null, createdAt: '2026-10-09T01:00:00Z' },
      { id: 'call-2', taskId: 'task-2', kind: 'MCP_SEARCH', provider: 'tavily',
        status: 'FAILED', costCny: null, reservedCny: 0.01, errorCode: 'MCP_UNAVAILABLE',
        createdAt: '2026-10-09T02:00:00Z' },
    ]
    if (path === '/api/generation-jobs/accounts/account-1') return job
    if (path === '/api/generation-jobs/job-1/triggers') return [{ id: 'trigger-1', triggerSource: 'SCHEDULED',
      status: 'FAILED', generationRunId: null, errorCode: 'MODEL_TIMEOUT' }]
    throw new Error(`Unexpected path ${path}`)
  })
})

describe('operations page', () => {
  it('shows budget pause, failed run, and distinguishes zero cost from unknown cost', async () => {
    const wrapper = mount(OperationsPage)
    await flushPromises()
    expect(wrapper.text()).toContain('BUDGET_LIMIT_REACHED')
    expect(wrapper.text()).toContain('系统月预算余额不足')
    expect(wrapper.text()).toContain('MODEL_TIMEOUT')
    expect(wrapper.text()).toContain('模型响应超时')
    expect(wrapper.text()).toContain('¥0.000000')
    expect(wrapper.text()).toContain('预留 ¥0.010000')
    expect(wrapper.get('[data-action="trigger-job"]').attributes('disabled')).toBeDefined()
    wrapper.unmount()
  })

  it('prevents a paid manual run while the job has unsaved changes', async () => {
    paused = false
    const wrapper = mount(OperationsPage)
    await flushPromises()
    await wrapper.get('.wide-field textarea').setValue('新要求')
    expect(wrapper.get('[data-action="trigger-job"]').attributes('disabled')).toBeDefined()
    await wrapper.get('[data-action="trigger-job"]').trigger('click')
    expect(postJsonWithCsrf).not.toHaveBeenCalled()
    await wrapper.get('[data-action="discard-job"]').trigger('click')
    expect(wrapper.get('[data-action="trigger-job"]').attributes('disabled')).toBeUndefined()
    wrapper.unmount()
  })

  it('updates the saved enabled state once and disables redundant saves', async () => {
    vi.mocked(putJsonWithCsrf).mockResolvedValue({ ...job, enabled: false })
    const wrapper = mount(OperationsPage)
    await flushPromises()
    expect(wrapper.get('[data-action="save-job"]').attributes('disabled')).toBeDefined()
    await wrapper.get('[data-action="toggle-job"]').trigger('click')
    await flushPromises()
    expect(putJsonWithCsrf).toHaveBeenCalledTimes(1)
    expect(wrapper.get('[data-action="toggle-job"]').text()).toContain('启用任务')
    expect(wrapper.get('[data-action="save-job"]').attributes('disabled')).toBeDefined()
    wrapper.unmount()
  })
})
