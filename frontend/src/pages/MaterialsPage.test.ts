import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, describe, expect, it, vi } from 'vitest'
import MaterialsPage from './MaterialsPage.vue'

afterEach(() => { vi.unstubAllGlobals(); vi.restoreAllMocks() })

describe('text materials', () => {
  it('imports Markdown text, previews it and deletes it with CSRF', async () => {
    const material = { id: 'material-1', title: '并发笔记', purpose: 'Java 面试',
      sourceUrl: null, fileName: '并发笔记.md', segmentCount: 1, content: 'volatile 保证可见性。',
      kind: 'TEXT', accountIds: [] }
    const { content, ...summary } = material
    const fetch = vi.fn()
      .mockResolvedValueOnce(Response.json([]))
      .mockResolvedValueOnce(Response.json([]))
      .mockResolvedValueOnce(Response.json({ headerName: 'X-CSRF-TOKEN', token: 'create' }))
      .mockResolvedValueOnce(Response.json(material, { status: 201 }))
      .mockResolvedValueOnce(Response.json([summary]))
      .mockResolvedValueOnce(Response.json(material))
      .mockResolvedValueOnce(Response.json({ headerName: 'X-CSRF-TOKEN', token: 'delete' }))
      .mockResolvedValueOnce(new Response(null, { status: 204 }))
      .mockResolvedValueOnce(Response.json([]))
    vi.stubGlobal('fetch', fetch)
    vi.spyOn(window, 'confirm').mockReturnValue(true)
    const wrapper = mount(MaterialsPage)
    await flushPromises()
    const input = wrapper.get('input[type="file"]')
    Object.defineProperty(input.element, 'files', { configurable: true,
      value: [{ name: '并发笔记.md', size: 30, text: async () => content }] })
    await input.trigger('change')
    await flushPromises()
    expect((wrapper.get('textarea').element as HTMLTextAreaElement).value).toBe(content)
    await wrapper.get('#material-purpose').setValue('Java 面试')
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(fetch.mock.calls[3][0]).toBe('/api/materials')
    expect(JSON.parse(fetch.mock.calls[3][1].body).fileName).toBe('并发笔记.md')
    expect(wrapper.get('.preview').text()).toContain('volatile')
    await wrapper.get('.item-actions button').trigger('click')
    await flushPromises()
    expect(fetch.mock.calls[5][0]).toBe('/api/materials/material-1')
    await wrapper.get('.item-actions button:last-child').trigger('click')
    await flushPromises()
    expect(fetch.mock.calls[7][1].method).toBe('DELETE')
    expect(fetch.mock.calls[7][1].headers['X-CSRF-TOKEN']).toBe('delete')
    expect(wrapper.find('.preview').exists()).toBe(false)
  })

  it('rejects an oversized file before reading or submitting it', async () => {
    const fetch = vi.fn().mockResolvedValue(Response.json([]))
    vi.stubGlobal('fetch', fetch)
    const wrapper = mount(MaterialsPage)
    await flushPromises()
    const input = wrapper.get('input[type="file"]')
    const read = vi.fn()
    Object.defineProperty(input.element, 'files', { configurable: true,
      value: [{ name: 'large.txt', size: 102_401, text: read }] })
    await input.trigger('change')
    expect(wrapper.get('[role="alert"]').text()).toContain('100 KiB')
    expect(read).not.toHaveBeenCalled()
    expect(fetch).toHaveBeenCalledTimes(2)
  })

  it('saves a link with only a user excerpt and the selected account', async () => {
    const saved = { id: 'link-1', title: 'Java 文档', purpose: '面试', sourceUrl: 'https://example.test/guide',
      fileName: null, segmentCount: 1, content: '用户摘录', kind: 'LINK', accountIds: ['account-1'] }
    const fetch = vi.fn().mockImplementation((path: string, options?: RequestInit) => {
      if (path === '/api/accounts') return Promise.resolve(Response.json([{ id: 'account-1', name: 'Java 账号' }]))
      if (path === '/api/auth/csrf') return Promise.resolve(Response.json({ headerName: 'X-CSRF-TOKEN', token: 'token' }))
      if (path === '/api/materials' && options?.method === 'POST') return Promise.resolve(Response.json(saved, { status: 201 }))
      return Promise.resolve(Response.json([]))
    })
    vi.stubGlobal('fetch', fetch)
    const wrapper = mount(MaterialsPage)
    await flushPromises()
    await wrapper.get('#material-kind').setValue('LINK')
    await wrapper.get('#material-title').setValue('Java 文档')
    await wrapper.get('#material-purpose').setValue('面试')
    await wrapper.get('#material-source').setValue('https://example.test/guide')
    await wrapper.get('#material-content').setValue('用户摘录')
    await wrapper.get('.account-choice input').setValue(true)
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    const post = fetch.mock.calls.find(call => call[1]?.method === 'POST')
    expect(post?.[0]).toBe('/api/materials')
    expect(JSON.parse(post?.[1].body)).toMatchObject({ kind: 'LINK', accountIds: ['account-1'], content: '用户摘录' })
    expect(fetch.mock.calls.some(call => call[0] === 'https://example.test/guide')).toBe(false)
  })

  it('submits a PDF as multipart without reading it as text', async () => {
    const saved = { id: 'pdf-1', title: 'Handout', purpose: '英语', sourceUrl: null,
      fileName: 'Handout.pdf', segmentCount: 1, content: 'English passage', kind: 'PDF', accountIds: [] }
    const fetch = vi.fn().mockImplementation((path: string, options?: RequestInit) => {
      if (path === '/api/auth/csrf') return Promise.resolve(Response.json({ headerName: 'X-CSRF-TOKEN', token: 'token' }))
      if (path === '/api/materials/pdf' && options?.method === 'POST') return Promise.resolve(Response.json(saved, { status: 201 }))
      return Promise.resolve(Response.json([]))
    })
    vi.stubGlobal('fetch', fetch)
    const wrapper = mount(MaterialsPage)
    await flushPromises()
    await wrapper.get('#material-kind').setValue('PDF')
    const file = new File(['%PDF'], 'Handout.pdf', { type: 'application/pdf' })
    const input = wrapper.get('input[type="file"]')
    Object.defineProperty(input.element, 'files', { configurable: true, value: [file] })
    await input.trigger('change')
    await wrapper.get('#material-purpose').setValue('英语')
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    const post = fetch.mock.calls.find(call => call[0] === '/api/materials/pdf')
    expect(post?.[1].body).toBeInstanceOf(FormData)
    expect((post?.[1].body as FormData).get('file')).toBe(file)
    expect(post?.[1].headers['Content-Type']).toBeUndefined()
  })
})
