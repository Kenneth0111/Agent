import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, expect, it, vi } from 'vitest'
import MetricsImportPanel from './MetricsImportPanel.vue'
import { HttpError, postMultipartWithCsrf } from '../api/http'

vi.mock('../api/http', async importOriginal => ({ ...await importOriginal<typeof import('../api/http')>(), postMultipartWithCsrf: vi.fn() }))
const row = { rowNumber: 2, status: 'READY', errorCode: null, data: {
  accountId: 'a', videoId: '000123', capturedAt: '2026-10-11T10:00:00Z',
  views: '9223372036854775807', likes: null, commentCount: '0', source: 'DEMO_QA',
} }
const preview = { canImport: true, rows: [row] }
beforeEach(() => vi.mocked(postMultipartWithCsrf).mockReset())
async function choose(wrapper: ReturnType<typeof mount>, size = 10) {
  const file = new File(['x'.repeat(size)], 'metrics.csv', { type: 'text/csv' })
  Object.defineProperty(wrapper.get('input[type=file]').element, 'files', { value: [file], configurable: true })
  await wrapper.get('input[type=file]').trigger('change')
}

it('previews exact counts without writing, then confirms the same file and refreshes', async () => {
  vi.mocked(postMultipartWithCsrf).mockResolvedValueOnce(preview)
    .mockResolvedValueOnce({ imported: 1, duplicates: 0, rows: [{ ...row, status: 'IMPORTED' }] })
  const wrapper = mount(MetricsImportPanel, { props: { accountId: 'a' } })
  expect(wrapper.get('[data-action=confirm]').attributes('disabled')).toBeDefined()
  await choose(wrapper)
  await wrapper.get('[data-action=preview]').trigger('click')
  await flushPromises()
  expect(postMultipartWithCsrf).toHaveBeenCalledTimes(1)
  expect(vi.mocked(postMultipartWithCsrf).mock.calls[0]?.[0]).toBe('/api/metrics/import/preview')
  expect(wrapper.get('tbody').text()).toContain('9223372036854775807')
  expect(wrapper.get('tbody').text()).toContain('未知')
  await wrapper.get('[data-action=confirm]').trigger('click')
  await flushPromises()
  expect(vi.mocked(postMultipartWithCsrf).mock.calls[1]?.[1].get('file')).toBe(vi.mocked(postMultipartWithCsrf).mock.calls[0]?.[1].get('file'))
  expect(wrapper.emitted('imported')).toHaveLength(1)
  expect(wrapper.text()).toContain('新增 1 条，跳过重复 0 条')
  expect(wrapper.get('[data-action=confirm]').attributes('disabled')).toBeDefined()
  wrapper.unmount()
})

it('blocks invalid or different-account rows and clears preview when a file changes', async () => {
  vi.mocked(postMultipartWithCsrf).mockResolvedValueOnce({ canImport: false, rows: [
    { rowNumber: 2, status: 'ERROR', errorCode: 'ACCOUNT_NOT_FOUND', data: null },
  ] }).mockResolvedValueOnce(preview).mockResolvedValueOnce({ ...preview, rows: [{ ...row, data: { ...row.data, accountId: 'b' } }] })
  const wrapper = mount(MetricsImportPanel, { props: { accountId: 'a' } })
  await choose(wrapper); await wrapper.get('[data-action=preview]').trigger('click'); await flushPromises()
  expect(wrapper.text()).toContain('账号不存在或无权访问')
  expect(wrapper.get('[data-action=confirm]').attributes('disabled')).toBeDefined()
  await choose(wrapper); await wrapper.get('[data-action=preview]').trigger('click'); await flushPromises()
  expect(wrapper.get('[data-action=confirm]').attributes('disabled')).toBeUndefined()
  await choose(wrapper)
  expect(wrapper.find('tbody').exists()).toBe(false)
  expect(wrapper.get('[data-action=confirm]').attributes('disabled')).toBeDefined()
  await wrapper.get('[data-action=preview]').trigger('click'); await flushPromises()
  expect(wrapper.get('[role=alert]').text()).toContain('账号 ID')
  expect(wrapper.get('[data-action=confirm]').attributes('disabled')).toBeDefined()
  wrapper.unmount()
})

it('shows fresh validation errors when confirmation conflicts, without announcing a write', async () => {
  vi.mocked(postMultipartWithCsrf).mockResolvedValueOnce(preview).mockRejectedValueOnce(new HttpError(422, undefined,
    { canImport: false, rows: [{ rowNumber: 2, status: 'ERROR', errorCode: 'SNAPSHOT_CONFLICT', data: null }] }))
  const wrapper = mount(MetricsImportPanel, { props: { accountId: 'a' } })
  await choose(wrapper); await wrapper.get('[data-action=preview]').trigger('click'); await flushPromises()
  await wrapper.get('[data-action=confirm]').trigger('click'); await flushPromises()
  expect(wrapper.text()).toContain('SNAPSHOT_CONFLICT')
  expect(wrapper.text()).toContain('整份文件未写入')
  expect(wrapper.emitted('imported')).toBeUndefined()
  expect(wrapper.get('[data-action=confirm]').attributes('disabled')).toBeDefined()
  wrapper.unmount()
})

it('ignores a late preview after changing account and rejects oversized files locally', async () => {
  let resolveOld: (value: unknown) => void = () => {}
  vi.mocked(postMultipartWithCsrf).mockReturnValueOnce(new Promise(resolve => { resolveOld = resolve }))
  const wrapper = mount(MetricsImportPanel, { props: { accountId: 'a' } })
  await choose(wrapper); await wrapper.get('[data-action=preview]').trigger('click')
  await wrapper.setProps({ accountId: 'b' })
  resolveOld(preview); await flushPromises()
  expect(wrapper.find('tbody').exists()).toBe(false)
  expect(wrapper.get('[data-action=confirm]').attributes('disabled')).toBeDefined()
  await choose(wrapper, 1_048_577)
  expect(wrapper.get('[role=alert]').text()).toContain('1 MiB')
  await wrapper.get('[data-action=preview]').trigger('click')
  expect(postMultipartWithCsrf).toHaveBeenCalledTimes(1)
  wrapper.unmount()
})

it('uses the comment endpoint and displays redacted text as plain text', async () => {
  vi.mocked(postMultipartWithCsrf).mockResolvedValueOnce({ canImport: true, rows: [{ ...row, redacted: true,
    data: { accountId: 'a', videoId: '000123', commentId: 'c1', text: '<img src=x> [邮箱]', publishedAt: null,
      capturedAt: row.data.capturedAt, source: 'DEMO_QA' },
  }] })
  const wrapper = mount(MetricsImportPanel, { props: { accountId: 'a' } })
  await wrapper.get('select').setValue('comments'); await choose(wrapper)
  await wrapper.get('[data-action=preview]').trigger('click'); await flushPromises()
  expect(vi.mocked(postMultipartWithCsrf).mock.calls[0]?.[0]).toBe('/api/metrics/import/comments/preview')
  expect(wrapper.get('tbody').text()).toContain('<img src=x> [邮箱]')
  expect(wrapper.get('tbody').text()).toContain('已脱敏')
  expect(wrapper.find('img').exists()).toBe(false)
  wrapper.unmount()
})
