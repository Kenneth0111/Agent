import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, expect, it, vi } from 'vitest'
import ReviewSchedulePanel from './ReviewSchedulePanel.vue'
import { putJsonWithCsrf, request } from '../api/http'

vi.mock('../api/http', async importOriginal => ({ ...await importOriginal<typeof import('../api/http')>(), request: vi.fn(), putJsonWithCsrf: vi.fn() }))
const empty = { schedulerEnabled: false, job: null, triggers: [] }
const job = { id: 'j1', accountId: 'a', dayOfWeek: 7, localTime: '09:00:00', timeZone: 'Asia/Shanghai', enabled: false, topic: '线程池' }
beforeEach(() => { vi.mocked(request).mockReset().mockResolvedValue(empty); vi.mocked(putJsonWithCsrf).mockReset() })

it('shows the server pause and saves a separate weekly review configuration without triggering', async () => {
  vi.mocked(putJsonWithCsrf).mockResolvedValue({ ...empty, job })
  const wrapper = mount(ReviewSchedulePanel, { props: { accountId: 'a' } })
  await flushPromises()
  expect(wrapper.text()).toContain('服务器定时复盘尚未开启')
  await wrapper.get('[aria-label=定时复盘关键词]').setValue('线程池')
  await wrapper.get('form').trigger('submit'); await flushPromises()
  expect(putJsonWithCsrf).toHaveBeenCalledWith('/api/metrics/review-jobs/accounts/a', {
    dayOfWeek: 7, localTime: '09:00', timeZone: 'Asia/Shanghai', enabled: false, topic: '线程池',
  })
  expect(wrapper.text()).toContain('设置已保存')
  expect(wrapper.text()).toContain('暂无定时复盘执行记录')
  wrapper.unmount()
})

it('refreshes records while preserving unsaved settings and emits a read-only report selection', async () => {
  const trigger = { id: 't1', accountId: 'a', runKey: '2026-10-05', status: 'NEEDS_REVIEW', reportId: 'r1', errorCode: 'MODEL_TIMEOUT',
    createdAt: '2026-10-11T01:05:00Z', request: { accountId: 'a', topic: '线程池', from: '2026-10-04T01:00:00Z', to: '2026-10-11T01:00:00Z', requestId: 'key' } }
  vi.mocked(request).mockResolvedValue({ schedulerEnabled: true, job, triggers: [trigger] })
  const wrapper = mount(ReviewSchedulePanel, { props: { accountId: 'a' } })
  await flushPromises(); await wrapper.get('[aria-label=定时复盘关键词]').setValue('Redis')
  await wrapper.get('.heading button').trigger('click'); await flushPromises()
  expect((wrapper.get('[aria-label=定时复盘关键词]').element as HTMLInputElement).value).toBe('Redis')
  expect(wrapper.text()).toContain('保留尚未保存的配置')
  expect(wrapper.get('tbody').text()).toContain('结果需人工核对')
  expect(wrapper.get('tbody').text()).toContain('MODEL_TIMEOUT')
  await wrapper.get('tbody button').trigger('click')
  expect(wrapper.emitted('report')).toEqual([['r1']])
  expect(putJsonWithCsrf).not.toHaveBeenCalled()
  wrapper.unmount()
})

it('clears account state and ignores late settings and confirmation from another account', async () => {
  let resolveOld: (value: unknown) => void = () => {}
  vi.mocked(request).mockReturnValueOnce(new Promise(resolve => { resolveOld = resolve })).mockResolvedValueOnce(empty)
  const wrapper = mount(ReviewSchedulePanel, { props: { accountId: 'a' } })
  await wrapper.setProps({ accountId: 'b' }); await flushPromises()
  resolveOld({ ...empty, job }); await flushPromises()
  expect((wrapper.get('[aria-label=定时复盘关键词]').element as HTMLInputElement).value).toBe('')
  vi.mocked(putJsonWithCsrf).mockReturnValueOnce(new Promise(resolve => { resolveOld = resolve }))
  await wrapper.get('[aria-label=定时复盘关键词]').setValue('Redis'); await wrapper.get('form').trigger('submit')
  await wrapper.setProps({ accountId: 'a' }); await flushPromises()
  resolveOld({ ...empty, job: { ...job, accountId: 'b', topic: 'Redis' } }); await flushPromises()
  expect(wrapper.text()).not.toContain('设置已保存')
  expect((wrapper.get('[aria-label=定时复盘关键词]').element as HTMLInputElement).value).toBe('')
  wrapper.unmount()
})

it('rejects foreign account payloads instead of enabling a configuration save', async () => {
  vi.mocked(request).mockResolvedValueOnce({ ...empty, job: { ...job, accountId: 'b' } })
  const wrapper = mount(ReviewSchedulePanel, { props: { accountId: 'a' } })
  await flushPromises()
  expect(wrapper.get('[role=alert]').text()).toContain('无法读取或保存配置')
  expect(wrapper.get('button[type=submit]').attributes('disabled')).toBeDefined()
  expect(putJsonWithCsrf).not.toHaveBeenCalled()
  wrapper.unmount()
})
