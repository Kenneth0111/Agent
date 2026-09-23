import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, describe, expect, it, vi } from 'vitest'
import App from './App.vue'

afterEach(() => vi.unstubAllGlobals())

describe('service connection', () => {
  it('shows available only after a successful health response', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response('{"status":"UP"}')))
    const wrapper = mount(App)
    await flushPromises()
    expect(wrapper.get('[role="status"]').text()).toContain('服务已连接')
    wrapper.unmount()
  })

  it('shows unavailable on network failure and lets the user retry', async () => {
    const fetch = vi.fn()
      .mockRejectedValueOnce(new TypeError('Failed to fetch'))
      .mockResolvedValueOnce(new Response('{"status":"UP"}'))
    vi.stubGlobal('fetch', fetch)
    const wrapper = mount(App)
    await flushPromises()
    expect(wrapper.get('[role="status"]').text()).toContain('暂时无法连接')
    await wrapper.get('button').trigger('click')
    await flushPromises()
    expect(wrapper.get('[role="status"]').text()).toContain('服务已连接')
    wrapper.unmount()
  })

  it.each([
    [503, '{"status":"DOWN"}'],
    [200, '{"status":"DOWN"}'],
    [200, '<html>Proxy error</html>'],
  ])('does not mistake HTTP %s with %s for a healthy API', async (status, body) => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(body, { status })))
    const wrapper = mount(App)
    await flushPromises()
    expect(wrapper.get('[role="status"]').text()).toContain('暂时无法连接')
    wrapper.unmount()
  })
})
