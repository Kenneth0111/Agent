import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, describe, expect, it, vi } from 'vitest'
import AccountsPage from './AccountsPage.vue'

afterEach(() => vi.unstubAllGlobals())

describe('account settings', () => {
  it('creates an account and edits only the selected account ID', async () => {
    const account = { id: 'owned-1', name: 'Java 与托福', audience: '程序员',
      positioning: '面试与跟读', columns: ['Java 面试', '托福跟读'], weeklyTarget: 3 }
    const fetch = vi.fn()
      .mockResolvedValueOnce(Response.json([]))
      .mockResolvedValueOnce(Response.json({ headerName: 'X-CSRF-TOKEN', token: 'create' }))
      .mockResolvedValueOnce(Response.json(account, { status: 201 }))
      .mockResolvedValueOnce(Response.json([account]))
      .mockResolvedValueOnce(Response.json({ headerName: 'X-CSRF-TOKEN', token: 'edit' }))
      .mockResolvedValueOnce(Response.json({ ...account, name: 'Java 八股与托福' }))
      .mockResolvedValueOnce(Response.json([{ ...account, name: 'Java 八股与托福' }]))
    vi.stubGlobal('fetch', fetch)
    const wrapper = mount(AccountsPage)
    await flushPromises()
    await wrapper.get('[name="name"]').setValue(account.name)
    await wrapper.get('[name="audience"]').setValue(account.audience)
    await wrapper.get('[name="positioning"]').setValue(account.positioning)
    await wrapper.get('[name="columns"]').setValue('Java 面试、托福跟读')
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(fetch.mock.calls[2][0]).toBe('/api/accounts')
    expect(fetch.mock.calls[2][1].method).toBe('POST')
    expect(JSON.parse(fetch.mock.calls[2][1].body).columns).toEqual(['Java 面试', '托福跟读'])
    expect(wrapper.text()).toContain('Java 与托福')
    await wrapper.get('.account-list button').trigger('click')
    await wrapper.get('[name="name"]').setValue('Java 八股与托福')
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(fetch.mock.calls[5][0]).toBe('/api/accounts/owned-1')
    expect(fetch.mock.calls[5][1].method).toBe('PUT')
    expect(wrapper.emitted('changed')).toHaveLength(2)
  })
})
