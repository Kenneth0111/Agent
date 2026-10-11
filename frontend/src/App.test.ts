import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, describe, expect, it, vi } from 'vitest'
import App from './App.vue'
import LoginPage from './pages/LoginPage.vue'

vi.mock('./pages/LoginPage.vue', () => ({ default: { template: '<div />' } }))

afterEach(() => {
  vi.unstubAllGlobals()
  window.history.replaceState({}, '', '/')
})

describe('service connection', () => {
  it('keeps a notification run pending until login and then passes it to generation', async () => {
    window.history.replaceState({}, '', '/?run=owned-run')
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response('{"status":"UP"}')))
    const wrapper = mount(App, { global: { stubs: {
      AccountsPage: true, MaterialsPage: true, ChatPage: true, SchedulePage: true,
      OperationsPage: true, NotificationPage: true,
      GenerationPage: { props: ['linkedRunId'], template: '<div data-test="linked-run">{{ linkedRunId }}</div>' },
    } } })
    await flushPromises()
    expect(wrapper.find('[data-test="linked-run"]').exists()).toBe(false)
    wrapper.findComponent(LoginPage).vm.$emit('authChange', true)
    await flushPromises()
    expect(wrapper.get('[data-test="linked-run"]').text()).toBe('owned-run')
    wrapper.unmount()
  })

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
