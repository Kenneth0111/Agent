import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, describe, expect, it, vi } from 'vitest'
import MaterialsPage from './MaterialsPage.vue'

afterEach(() => { vi.unstubAllGlobals(); vi.restoreAllMocks() })

describe('text materials', () => {
  it('imports Markdown text, previews it and deletes it with CSRF', async () => {
    const material = { id: 'material-1', title: '并发笔记', purpose: 'Java 面试',
      sourceUrl: null, fileName: '并发笔记.md', segmentCount: 1, content: 'volatile 保证可见性。' }
    const { content, ...summary } = material
    const fetch = vi.fn()
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
    expect(fetch.mock.calls[2][0]).toBe('/api/materials')
    expect(JSON.parse(fetch.mock.calls[2][1].body).fileName).toBe('并发笔记.md')
    expect(wrapper.get('.preview').text()).toContain('volatile')
    await wrapper.get('.item-actions button').trigger('click')
    await flushPromises()
    expect(fetch.mock.calls[4][0]).toBe('/api/materials/material-1')
    await wrapper.get('.item-actions button:last-child').trigger('click')
    await flushPromises()
    expect(fetch.mock.calls[6][1].method).toBe('DELETE')
    expect(fetch.mock.calls[6][1].headers['X-CSRF-TOKEN']).toBe('delete')
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
    expect(fetch).toHaveBeenCalledTimes(1)
  })
})
