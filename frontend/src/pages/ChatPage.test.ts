import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, describe, expect, it, vi } from 'vitest'
import ChatPage from './ChatPage.vue'

afterEach(() => vi.unstubAllGlobals())

describe('creator chat', () => {
  it('uses only the returned account and displays a model summary', async () => {
    const fetch = vi.fn()
      .mockResolvedValueOnce(Response.json([{ id: 'java', name: 'Java 面试快问快答', positioning: '面向 Java 程序员', columns: ['并发'], weeklyTarget: 2 }]))
      .mockResolvedValueOnce(Response.json({ headerName: 'X-CSRF-TOKEN', token: 'csrf' }))
      .mockResolvedValueOnce(Response.json({ accountId: 'java', summary: '本周聚焦 Java 并发面试快问快答。' }))
    vi.stubGlobal('fetch', fetch)
    const wrapper = mount(ChatPage)
    await flushPromises()
    expect(wrapper.get('select').text()).toContain('Java 面试快问快答')
    await wrapper.get('button').trigger('click')
    await flushPromises()
    expect(wrapper.get('[role="status"]').text()).toContain('本周聚焦')
    expect(fetch.mock.calls[2][0]).toBe('/api/agent/account-summaries')
    expect(JSON.parse(fetch.mock.calls[2][1].body)).toEqual({ accountId: 'java' })
    expect(fetch.mock.calls[2][1].headers['X-CSRF-TOKEN']).toBe('csrf')
    wrapper.unmount()
  })

  it('makes an unconfigured model visible instead of inventing a summary', async () => {
    vi.stubGlobal('fetch', vi.fn()
      .mockResolvedValueOnce(Response.json([{ id: 'java', name: 'Java 面试快问快答', positioning: '面向 Java 程序员', columns: [], weeklyTarget: 2 }]))
      .mockResolvedValueOnce(Response.json({ headerName: 'X-CSRF-TOKEN', token: 'csrf' }))
      .mockResolvedValueOnce(Response.json({ code: 'MODEL_NOT_CONFIGURED' }, { status: 503 })))
    const wrapper = mount(ChatPage)
    await flushPromises()
    await wrapper.get('button').trigger('click')
    await flushPromises()
    expect(wrapper.get('[role="alert"]').text()).toContain('模型尚未配置')
    expect(wrapper.find('[role="status"]').exists()).toBe(false)
    wrapper.unmount()
  })
})
