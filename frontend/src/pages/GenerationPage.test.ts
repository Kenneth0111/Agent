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
    if (path.startsWith('/api/generations/week-plans?')) return []
    throw new Error(`Unexpected path ${path}`)
  })
})

describe('content generation page', () => {
  it('ignores late topic and week responses after switching accounts', async () => {
    let finishTopics!: (value: unknown) => void
    let finishWeeks!: (value: unknown) => void
    const otherTopic = { ...topic, id: 'topic-2', accountId: 'account-2',
      topic: { ...topic.topic, title: '新账号选题' } }
    vi.mocked(request).mockImplementation(async path => {
      if (path === '/api/accounts') return [account, { id: 'account-2', name: '其他账号' }]
      if (path === '/api/materials') return [material]
      if (path === '/api/generations/topics?accountId=account-1') return new Promise(resolve => { finishTopics = resolve })
      if (path === '/api/generations/week-plans?accountId=account-1') return new Promise(resolve => { finishWeeks = resolve })
      if (path === '/api/generations/topics?accountId=account-2') return [otherTopic]
      return []
    })
    const wrapper = mount(GenerationPage)
    await flushPromises()
    await wrapper.get('#generation-account').setValue('account-2')
    await flushPromises()
    finishTopics([topic])
    finishWeeks([{ id: 'week-1', accountId: 'account-1', status: 'DRAFT',
      items: [{ column: 'Java 面试', topicId: 'topic-1', scriptId: 'script-1' }] }])
    await flushPromises()
    expect(wrapper.get('#generation-topic').text()).toContain('新账号选题')
    expect(wrapper.get('#generation-topic').text()).not.toContain('volatile 快问快答')
    expect(wrapper.findAll('.week-result')).toHaveLength(0)
    wrapper.unmount()
  })

  it('keeps the selected topic script when an older script request finishes late', async () => {
    let finishOld!: (value: unknown) => void
    const makeScript = (id: string, topicId: string, spokenText: string) => ({ id, topicId,
      script: { spokenText, shootingNotes: '口播', sourceIds: ['material-1'] },
      status: 'DRAFT', version: 1, conversationId: null })
    vi.mocked(request).mockImplementation(async path => {
      if (path === '/api/accounts') return [account]
      if (path === '/api/materials') return [material]
      if (path.startsWith('/api/generations/topics?')) return [topic, { ...topic, id: 'topic-2' }]
      if (path === '/api/generations/scripts?topicId=topic-1') return new Promise(resolve => { finishOld = resolve })
      if (path === '/api/generations/scripts?topicId=topic-2') return [makeScript('script-2', 'topic-2', '新选题脚本')]
      return []
    })
    const wrapper = mount(GenerationPage)
    await flushPromises()
    await wrapper.get('#generation-topic').setValue('topic-2')
    await flushPromises()
    finishOld([makeScript('script-1', 'topic-1', '旧选题脚本')])
    await flushPromises()
    expect(wrapper.get('.script').text()).toContain('新选题脚本')
    expect(wrapper.get('.script').text()).not.toContain('旧选题脚本')
    wrapper.unmount()
  })

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

  it('sends the selected script version and conversation when revising', async () => {
    const script = { id: 'script-1', topicId: 'topic-1', script: { spokenText: '旧稿', shootingNotes: '录屏', sourceIds: ['material-1'] },
      status: 'DRAFT', version: 2, conversationId: 'conversation-1' }
    vi.mocked(request).mockImplementation(async path => {
      if (path === '/api/accounts') return [account]
      if (path === '/api/materials') return [material]
      if (path.startsWith('/api/generations/topics?')) return [topic]
      if (path.startsWith('/api/generations/scripts?')) return [script]
      if (path.startsWith('/api/generations/week-plans?')) return []
      throw new Error(`Unexpected path ${path}`)
    })
    vi.mocked(postJsonWithCsrf).mockResolvedValue({ ...script, version: 3 })
    const wrapper = mount(GenerationPage)
    await flushPromises()
    await wrapper.get('#revision-script-1').setValue('改成口播')
    await wrapper.findAll('button').find(button => button.text() === '保存新版本')!.trigger('click')
    await flushPromises()
    expect(vi.mocked(postJsonWithCsrf)).toHaveBeenCalledWith('/api/generations/scripts/script-1/revise',
      { conversationId: 'conversation-1', expectedVersion: 2, instruction: '改成口播' }, 65_000)
    wrapper.unmount()
  })

  it('submits a weekly draft with two distinct Java sources and one English source', async () => {
    vi.mocked(request).mockImplementation(async path => {
      if (path === '/api/accounts') return [account]
      if (path === '/api/materials') return [material,
        { ...material, id: 'material-2', title: '集合资料' },
        { ...material, id: 'material-3', title: '英语短文' }]
      if (path.startsWith('/api/generations/topics?')) return [topic]
      if (path.startsWith('/api/generations/scripts?')) return []
      if (path.startsWith('/api/generations/week-plans?')) return []
      throw new Error(`Unexpected path ${path}`)
    })
    vi.mocked(postJsonWithCsrf).mockResolvedValue({ id: 'run-week', accountId: 'account-1',
      mode: 'WEEK_PLAN', status: 'SUCCEEDED', resultId: 'week-1', errorCode: null })
    const wrapper = mount(GenerationPage)
    await flushPromises()
    await wrapper.get('#generation-instruction').setValue('本周三条内容')
    await wrapper.get('#week-java-1').setValue('material-1')
    await wrapper.get('#week-java-2').setValue('material-2')
    await wrapper.get('#week-english').setValue('material-3')
    await wrapper.findAll('button').find(button => button.text() === '生成整周草稿')!.trigger('click')
    await flushPromises()
    expect(vi.mocked(postJsonWithCsrf)).toHaveBeenCalledWith('/api/generations', expect.objectContaining({
      mode: 'WEEK_PLAN', slots: [
        { column: 'Java 面试', materialIds: ['material-1'], instruction: '第一条 Java 快问快答' },
        { column: 'Java 面试', materialIds: ['material-2'], instruction: '第二条 Java 快问快答，避免重复第一条' },
        { column: '英语跟读', materialIds: ['material-3'], instruction: '一条英语跟读练习' },
      ],
    }), 180_000)
    wrapper.unmount()
  })

  it('reloads a saved week and opens its selected script with a source after refresh', async () => {
    const englishTopic = { ...topic, id: 'topic-3', topic: { ...topic.topic,
      column: '英语跟读', title: '原创短句跟读', sourceIds: ['material-3'] } }
    const englishScript = { id: 'script-3', topicId: 'topic-3', script: {
      spokenText: 'Small steps can build lasting habits.', shootingNotes: '口播', sourceIds: ['material-3'] },
    status: 'DRAFT', version: 1, conversationId: null }
    vi.mocked(request).mockImplementation(async path => {
      if (path === '/api/accounts') return [account]
      if (path === '/api/materials') return [material,
        { ...material, id: 'material-3', title: '原创英语短文' }]
      if (path.startsWith('/api/generations/topics?')) return [topic, englishTopic]
      if (path === '/api/generations/scripts?topicId=topic-3') return [englishScript]
      if (path.startsWith('/api/generations/scripts?')) return []
      if (path.startsWith('/api/generations/week-plans?')) return [{ id: 'week-1', accountId: 'account-1', status: 'DRAFT',
        items: [{ column: '英语跟读', topicId: 'topic-3', scriptId: 'script-3' }] }]
      throw new Error(`Unexpected path ${path}`)
    })
    const wrapper = mount(GenerationPage)
    await flushPromises()
    await wrapper.get('.week-topic').trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('Small steps can build lasting habits.')
    expect(wrapper.text()).toContain('原创英语短文')
    expect(vi.mocked(request)).toHaveBeenCalledWith('/api/generations/scripts?topicId=topic-3')
    wrapper.unmount()
  })
})
