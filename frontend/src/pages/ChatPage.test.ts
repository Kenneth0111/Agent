import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, describe, expect, it, vi } from 'vitest'
import ChatPage from './ChatPage.vue'

afterEach(() => { vi.useRealTimers(); vi.unstubAllGlobals() })

describe('creator chat', () => {
  it('clears the previous account answer when switching accounts', async () => {
    vi.stubGlobal('fetch', vi.fn()
      .mockResolvedValueOnce(Response.json([
        { id: 'java', name: 'Java', positioning: '面试', columns: [], weeklyTarget: 2 },
        { id: 'english', name: 'English', positioning: '跟读', columns: [], weeklyTarget: 1 },
      ]))
      .mockResolvedValueOnce(Response.json({ headerName: 'X-CSRF-TOKEN', token: 'csrf' }))
      .mockResolvedValueOnce(Response.json({ accountId: 'java', summary: 'Java 账号摘要' })))
    const wrapper = mount(ChatPage)
    await flushPromises()
    await wrapper.get('button').trigger('click')
    await flushPromises()
    expect(wrapper.get('[role="status"]').text()).toContain('Java 账号摘要')
    await wrapper.get('select').setValue('english')
    expect(wrapper.find('[role="status"]').exists()).toBe(false)
    wrapper.unmount()
  })

  it.each(['summary', 'research'] as const)('waits for a %s result that needs more than ten seconds', async mode => {
    vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout'] })
    vi.stubGlobal('fetch', vi.fn()
      .mockResolvedValueOnce(Response.json([{ id: 'java', name: 'Java 账号', positioning: '面试', columns: [], weeklyTarget: 2 }]))
      .mockResolvedValueOnce(Response.json({ headerName: 'X-CSRF-TOKEN', token: 'csrf' }))
      .mockImplementationOnce((_path: string, options: RequestInit) => new Promise<Response>((resolve, reject) => {
        options.signal?.addEventListener('abort', () => reject(new DOMException('Aborted', 'AbortError')))
        setTimeout(() => resolve(Response.json(mode === 'summary'
          ? { accountId: 'java', summary: '慢速返回的摘要' }
          : { status: 'MATCHED', answer: '慢速返回的答案', sources: [] })), 12_000)
      })))
    const wrapper = mount(ChatPage)
    await flushPromises()
    if (mode === 'research') await wrapper.get('#agent-query').setValue('JDK 发布说明')
    await wrapper.findAll('button')[mode === 'summary' ? 0 : 1].trigger('click')
    await flushPromises()
    await vi.advanceTimersByTimeAsync(11_000)
    await flushPromises()
    expect(wrapper.find('[role="alert"]').exists()).toBe(false)
    expect(wrapper.get('select').attributes('disabled')).toBeDefined()
    await vi.advanceTimersByTimeAsync(1_000)
    await flushPromises()
    expect(wrapper.get('[role="status"]').text()).toContain('慢速返回')
    wrapper.unmount()
  })

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

  it('asks within the selected account and displays local evidence with its source', async () => {
    const fetch = vi.fn()
      .mockResolvedValueOnce(Response.json([{ id: 'java', name: 'Java 账号', positioning: '面试', columns: [], weeklyTarget: 2 }]))
      .mockResolvedValueOnce(Response.json({ headerName: 'X-CSRF-TOKEN', token: 'csrf' }))
      .mockResolvedValueOnce(Response.json({ status: 'MATCHED', answer: 'volatile 保证可见性 [1]', sources: [
        { materialId: 'm1', title: '并发笔记', snippet: 'volatile 保证可见性',
          sourceUrl: 'https://example.test/notes', fileName: null, kind: 'TEXT' },
      ] }))
    vi.stubGlobal('fetch', fetch)
    const wrapper = mount(ChatPage)
    await flushPromises()
    await wrapper.get('#agent-query').setValue('volatile 为什么能保证可见性？')
    await wrapper.findAll('button')[1].trigger('click')
    await flushPromises()
    expect(JSON.parse(fetch.mock.calls[2][1].body)).toEqual({ accountId: 'java', query: 'volatile 为什么能保证可见性？' })
    expect(wrapper.get('.research-answer').text()).toContain('volatile 保证可见性')
    expect(wrapper.get('.source-list a').attributes('href')).toBe('https://example.test/notes')
    wrapper.unmount()
  })

  it('shows insufficient material without a fabricated answer', async () => {
    vi.stubGlobal('fetch', vi.fn()
      .mockResolvedValueOnce(Response.json([{ id: 'java', name: 'Java 账号', positioning: '面试', columns: [], weeklyTarget: 2 }]))
      .mockResolvedValueOnce(Response.json({ headerName: 'X-CSRF-TOKEN', token: 'csrf' }))
      .mockResolvedValueOnce(Response.json({ status: 'INSUFFICIENT_MATERIAL', answer: null, sources: [],
        webSearchStatus: 'MCP_NOT_CONFIGURED' })))
    const wrapper = mount(ChatPage)
    await flushPromises()
    await wrapper.get('#agent-query').setValue('没有资料的问题')
    await wrapper.findAll('button')[1].trigger('click')
    await flushPromises()
    expect(wrapper.get('.research-answer').text()).toContain('资料不足')
    expect(wrapper.get('.research-answer').text()).toContain('联网搜索尚未配置')
    expect(wrapper.find('.source-list').exists()).toBe(false)
    wrapper.unmount()
  })

  it('labels external search results and shows a failed external search clearly', async () => {
    const fetch = vi.fn()
      .mockResolvedValueOnce(Response.json([{ id: 'java', name: 'Java 账号', positioning: '面试', columns: [], weeklyTarget: 2 }]))
      .mockResolvedValueOnce(Response.json({ headerName: 'X-CSRF-TOKEN', token: 'csrf' }))
      .mockResolvedValueOnce(Response.json({ status: 'MATCHED', answer: '网页线索 [1]', sources: [
        { materialId: 'https://example.test/web', title: '网页文章', snippet: '一段网页摘要',
          sourceUrl: 'https://example.test/web', fileName: null, kind: 'WEB' },
      ] }))
      .mockResolvedValueOnce(Response.json({ headerName: 'X-CSRF-TOKEN', token: 'csrf' }))
      .mockResolvedValueOnce(Response.json({ code: 'MCP_NOT_CONFIGURED' }, { status: 503 }))
    vi.stubGlobal('fetch', fetch)
    const wrapper = mount(ChatPage)
    await flushPromises()
    await wrapper.get('#agent-query').setValue('外部问题')
    await wrapper.findAll('button')[1].trigger('click')
    await flushPromises()
    expect(wrapper.get('.source-list').text()).toContain('网页来源')
    expect(wrapper.get('.source-list a').attributes('href')).toBe('https://example.test/web')
    await wrapper.findAll('button')[1].trigger('click')
    await flushPromises()
    expect(wrapper.get('[role="alert"]').text()).toContain('联网搜索尚未配置')
    wrapper.unmount()
  })
})
