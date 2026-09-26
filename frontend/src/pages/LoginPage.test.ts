import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, describe, expect, it, vi } from 'vitest'
import LoginPage from './LoginPage.vue'

afterEach(() => vi.unstubAllGlobals())

describe('login workspace', () => {
  it('signs in with CSRF and shows the current user; signs out with a fresh token', async () => {
    const fetch = vi.fn()
      .mockResolvedValueOnce(new Response('', { status: 401 }))
      .mockResolvedValueOnce(Response.json({ headerName: 'X-CSRF-TOKEN', token: 'before-login' }))
      .mockResolvedValueOnce(new Response(null, { status: 204 }))
      .mockResolvedValueOnce(Response.json({ id: 1, email: 'a@example.test', displayName: '创作者 A' }))
      .mockResolvedValueOnce(Response.json({ headerName: 'X-CSRF-TOKEN', token: 'after-login' }))
      .mockResolvedValueOnce(new Response(null, { status: 204 }))
    vi.stubGlobal('fetch', fetch)
    const wrapper = mount(LoginPage)
    await flushPromises()
    await wrapper.get('input[type="email"]').setValue('a@example.test')
    await wrapper.get('input[type="password"]').setValue('example-password')
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(wrapper.text()).toContain('创作者 A')
    expect(wrapper.find('input[type="password"]').exists()).toBe(false)
    expect(fetch.mock.calls[2][0]).toBe('/api/auth/login')
    expect(fetch.mock.calls[2][1].headers['X-CSRF-TOKEN']).toBe('before-login')
    expect(new URLSearchParams(fetch.mock.calls[2][1].body).get('email')).toBe('a@example.test')
    await wrapper.get('button').trigger('click')
    await flushPromises()
    expect(fetch.mock.calls[5][1].headers['X-CSRF-TOKEN']).toBe('after-login')
    expect(wrapper.find('input[type="email"]').exists()).toBe(true)
    wrapper.unmount()
  })

  it('explains rejected credentials without showing a successful session', async () => {
    vi.stubGlobal('fetch', vi.fn()
      .mockResolvedValueOnce(new Response('', { status: 401 }))
      .mockResolvedValueOnce(Response.json({ headerName: 'X-CSRF-TOKEN', token: 'test' }))
      .mockResolvedValueOnce(new Response('', { status: 401 })))
    const wrapper = mount(LoginPage)
    await flushPromises()
    await wrapper.get('input[type="email"]').setValue('a@example.test')
    await wrapper.get('input[type="password"]').setValue('wrong')
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(wrapper.get('[role="alert"]').text()).toContain('邮箱或密码不正确')
    expect(wrapper.get('button').attributes('disabled')).toBeUndefined()
    wrapper.unmount()
  })

  it('shows a recoverable message when the server is unavailable', async () => {
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new TypeError('Network error')))
    const wrapper = mount(LoginPage)
    await flushPromises()
    expect(wrapper.get('[role="alert"]').text()).toContain('暂时无法连接')
    expect(wrapper.find('form').exists()).toBe(true)
    wrapper.unmount()
  })
})
