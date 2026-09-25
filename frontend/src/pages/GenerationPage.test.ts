import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import GenerationPage from './GenerationPage.vue'
import { postJsonWithCsrf, request } from '../api/http'

vi.mock('../api/http', () => ({ request: vi.fn(), postJsonWithCsrf: vi.fn() }))

const account = { id: 'account-1', name: '技术账号' }
const material = { id: 'material-1', title: 'Java 21 资料', sourceUrl: null, accountIds: ['account-1'] }
const topic = { id: 'topic-1', accountId: 'account-1', topic: { column: 'Java 面试', title: 'volatile 快问快答',
  audience: '程序员', angle: '可见性', hook: '问题', outline: '简答与解释', sourceIds: ['material-1'], rationale: '资料' } }

beforeEach(() => {
  vi.mocked(request).mockReset()
  vi.mocked(postJsonWithCsrf).mockReset()
  vi.mocked(request).mockImplementation(async path => {
    if (path === '/api/accounts') return [account]
    if (path === '/api/materials') return [material]
    if (path.startsWith('/api/generations/topics?')) return [topic]
    if (path.startsWith('/api/generations/scripts?')) return []
    throw new Error(`Unexpected path ${path}`)
  })
})

describe('content generation page', () => {
  it('loads saved drafts after mount and shows their source', async () => {
    const wrapper = mount(GenerationPage)
    await flushPromises()
    expect(wrapper.text()).toContain('volatile 快问快答')
    expect(wrapper.text()).toContain('Java 21 资料')
    expect(vi.mocked(request)).toHaveBeenCalledWith('/api/generations/topics?accountId=account-1')
    wrapper.unmount()
  })

  it('prevents duplicate submissions while a generation is running', async () => {
    let resolve!: (value: unknown) => void
    vi.mocked(postJsonWithCsrf).mockReturnValue(new Promise(value => { resolve = value }))
    const wrapper = mount(GenerationPage)
    await flushPromises()
    await wrapper.get('#generation-instruction').setValue('讲 volatile 的可见性')
    await wrapper.findAll('button').find(button => button.text().includes('生成选题'))!.trigger('click')
    await wrapper.findAll('button').find(button => button.text().includes('生成中'))!.trigger('click')
    expect(vi.mocked(postJsonWithCsrf)).toHaveBeenCalledTimes(1)
    resolve({ id: 'run-1', accountId: 'account-1', mode: 'TOPICS', status: 'SUCCEEDED', resultId: 'topic-1', errorCode: null })
    await flushPromises()
    expect(wrapper.text()).toContain('已保存草稿')
    wrapper.unmount()
  })
})
