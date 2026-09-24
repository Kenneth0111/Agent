import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, describe, expect, it, vi } from 'vitest'
import RegisterPage from './RegisterPage.vue'

afterEach(() => vi.unstubAllGlobals())

describe('invitation registration', () => {
  it('submits the invitation and returns to login after a successful registration', async () => {
    const fetch = vi.fn()
      .mockResolvedValueOnce(Response.json({ headerName: 'X-CSRF-TOKEN', token: 'register-token' }))
      .mockResolvedValueOnce(Response.json({ id: 3, email: 'new@example.test', displayName: '新创作者' }, { status: 201 }))
    vi.stubGlobal('fetch', fetch)
    const wrapper = mount(RegisterPage)
    await wrapper.get('input[name="invitationCode"]').setValue('invite-code-123')
    await wrapper.get('input[name="email"]').setValue('new@example.test')
    await wrapper.get('input[name="displayName"]').setValue('新创作者')
    await wrapper.get('input[name="password"]').setValue('long-enough-password')
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(wrapper.text()).toContain('注册成功')
    expect(fetch.mock.calls[1][0]).toBe('/api/auth/register')
    expect(fetch.mock.calls[1][1].headers['X-CSRF-TOKEN']).toBe('register-token')
    expect(JSON.parse(fetch.mock.calls[1][1].body)).toMatchObject({ invitationCode: 'invite-code-123' })
    await wrapper.get('button').trigger('click')
    expect(wrapper.emitted('back')).toHaveLength(1)
  })

  it('keeps the form available when registration is rejected', async () => {
    vi.stubGlobal('fetch', vi.fn()
      .mockResolvedValueOnce(Response.json({ headerName: 'X-CSRF-TOKEN', token: 'register-token' }))
      .mockResolvedValueOnce(Response.json({ code: 'REGISTRATION_REJECTED' }, { status: 400 })))
    const wrapper = mount(RegisterPage)
    await wrapper.get('input[name="invitationCode"]').setValue('expired-code')
    await wrapper.get('input[name="email"]').setValue('new@example.test')
    await wrapper.get('input[name="displayName"]').setValue('新创作者')
    await wrapper.get('input[name="password"]').setValue('long-enough-password')
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(wrapper.get('[role="alert"]').text()).toContain('邀请码无效、已过期或已使用')
    expect(wrapper.get('button').attributes('disabled')).toBeUndefined()
  })
})
