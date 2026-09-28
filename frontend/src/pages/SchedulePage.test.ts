import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import SchedulePage from './SchedulePage.vue'
import { postJsonWithCsrf, putJsonWithCsrf, request } from '../api/http'

vi.mock('../api/http', () => ({ request: vi.fn(), postJsonWithCsrf: vi.fn(), putJsonWithCsrf: vi.fn() }))

const account = { id: 'account-1', name: '创作账号', columns: ['Java 面试', '英语跟读'], weeklyTarget: 3 }
const monday = '2026-09-28'
const script = { id: 'script-1', topicId: 'topic-1', status: 'DRAFT', version: 1,
  script: { spokenText: '旧口播稿', shootingNotes: '录屏', sourceIds: ['material-1'] } }
const week = { id: 'week-1', accountId: 'account-1', weekStart: monday, items: [
  { id: 'item-1', column: 'Java 面试', scheduledDate: monday, topicId: 'topic-1', scriptId: 'script-1', version: 1 },
] }

beforeEach(() => {
  vi.mocked(request).mockReset()
  vi.mocked(postJsonWithCsrf).mockReset()
  vi.mocked(putJsonWithCsrf).mockReset()
  vi.mocked(request).mockImplementation(async path => {
    if (path === '/api/accounts') return [account]
    if (path.startsWith('/api/generations/week-plans?')) return []
    if (path.startsWith('/api/schedules/weeks?')) return week
    if (path === '/api/generations/scripts/script-1') return script
    throw new Error(`Unexpected path ${path}`)
  })
})

describe('weekly schedule page', () => {
  it('shows saved scripts, warns on unsaved edits, and confirms the saved version', async () => {
    vi.mocked(putJsonWithCsrf).mockResolvedValue({ ...script, version: 2,
      script: { ...script.script, spokenText: '新口播稿' } })
    vi.mocked(postJsonWithCsrf).mockResolvedValue({ ...script, version: 3, status: 'CONFIRMED',
      script: { ...script.script, spokenText: '新口播稿' } })
    const wrapper = mount(SchedulePage)
    await flushPromises()
    await wrapper.get('#schedule-week').setValue(monday)
    await flushPromises()
    expect((wrapper.get('#schedule-script-item-1').element as HTMLTextAreaElement).value).toBe('旧口播稿')
    await wrapper.get('#schedule-script-item-1').setValue('新口播稿')
    expect(wrapper.text()).toContain('有未保存修改')
    expect(wrapper.get('#schedule-week').attributes('disabled')).toBeDefined()
    await wrapper.get('[data-action="save-script"]').trigger('click')
    await flushPromises()
    expect(vi.mocked(putJsonWithCsrf)).toHaveBeenCalledWith('/api/generations/scripts/script-1',
      { expectedVersion: 1, spokenText: '新口播稿', shootingNotes: '录屏' })
    expect(wrapper.text()).not.toContain('有未保存修改')
    await wrapper.get('[data-action="confirm-script"]').trigger('click')
    await flushPromises()
    expect(vi.mocked(postJsonWithCsrf)).toHaveBeenCalledWith('/api/generations/scripts/script-1/confirm',
      { expectedVersion: 2 })
    expect(wrapper.text()).toContain('已确认')
    wrapper.unmount()
  })

  it('keeps a saved date when returning to the week', async () => {
    let savedWeek = week
    vi.mocked(request).mockImplementation(async path => {
      if (path === '/api/accounts') return [account]
      if (path.startsWith('/api/generations/week-plans?')) return []
      if (path.includes('weekStart=2026-10-05')) return { ...week, id: 'week-2', weekStart: '2026-10-05', items: [] }
      if (path.startsWith('/api/schedules/weeks?')) return savedWeek
      if (path === '/api/generations/scripts/script-1') return script
      throw new Error(`Unexpected path ${path}`)
    })
    vi.mocked(putJsonWithCsrf).mockImplementation(async () => {
      const item = { ...week.items[0], scheduledDate: '2026-09-29', version: 2 }
      savedWeek = { ...week, items: [item] }
      return item
    })
    const wrapper = mount(SchedulePage)
    await flushPromises()
    await wrapper.get('#schedule-week').setValue(monday)
    await flushPromises()
    await wrapper.get('#schedule-date-item-1').setValue('2026-09-29')
    await wrapper.get('[data-action="save-date"]').trigger('click')
    await flushPromises()
    expect(vi.mocked(putJsonWithCsrf)).toHaveBeenCalledWith('/api/schedules/items/item-1/date',
      { expectedVersion: 1, scheduledDate: '2026-09-29' })
    expect(wrapper.text()).not.toContain('有未保存修改')
    await wrapper.get('#schedule-week').setValue('2026-10-05')
    await flushPromises()
    await wrapper.get('#schedule-week').setValue(monday)
    await flushPromises()
    expect((wrapper.get('#schedule-date-item-1').element as HTMLInputElement).value).toBe('2026-09-29')
    wrapper.unmount()
  })

  it('keeps a different unsaved field when saving a script', async () => {
    vi.mocked(putJsonWithCsrf).mockResolvedValue({ ...script, version: 2,
      script: { ...script.script, spokenText: '新口播稿' } })
    const wrapper = mount(SchedulePage)
    await flushPromises()
    await wrapper.get('#schedule-date-item-1').setValue('2026-09-29')
    await wrapper.get('#schedule-script-item-1').setValue('新口播稿')
    await wrapper.get('[data-action="save-script"]').trigger('click')
    await flushPromises()
    expect((wrapper.get('#schedule-date-item-1').element as HTMLInputElement).value).toBe('2026-09-29')
    expect(wrapper.text()).toContain('有未保存修改')
    wrapper.unmount()
  })

  it('loads all three scripts in an attached weekly batch', async () => {
    const items = [0, 1, 2].map(index => ({ ...week.items[0], id: `item-${index}`,
      scriptId: `script-${index}`, topicId: `topic-${index}`,
      column: index === 2 ? '英语跟读' : 'Java 面试' }))
    vi.mocked(request).mockImplementation(async path => {
      if (path === '/api/accounts') return [account]
      if (path.startsWith('/api/generations/week-plans?')) return []
      if (path.startsWith('/api/schedules/weeks?')) return { ...week, items }
      const match = path.match(/^\/api\/generations\/scripts\/script-(\d)$/)
      if (match) return { ...script, id: `script-${match[1]}`, topicId: `topic-${match[1]}`,
        script: { ...script.script, spokenText: `第 ${match[1]} 条口播稿` } }
      throw new Error(`Unexpected path ${path}`)
    })
    const wrapper = mount(SchedulePage)
    await flushPromises()
    expect(wrapper.findAll('.schedule-item')).toHaveLength(3)
    for (let index = 0; index < 3; index++) {
      expect((wrapper.get(`#schedule-script-item-${index}`).element as HTMLTextAreaElement).value)
        .toBe(`第 ${index} 条口播稿`)
    }
    wrapper.unmount()
  })
})
