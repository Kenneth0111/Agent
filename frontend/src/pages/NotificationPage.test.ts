import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import NotificationPage from './NotificationPage.vue'
import { HttpError, putJsonWithCsrf, request } from '../api/http'

vi.mock('../api/http', async importOriginal => ({ ...await importOriginal<typeof import('../api/http')>(),
  request: vi.fn(), putJsonWithCsrf: vi.fn() }))

const saved = { emailRecipient: 'owner@example.test', smtpFromEmail: 'sender@example.test',
  emailEnabled: false, smtpHost: 'smtp.example.test', smtpPort: 587,
  smtpUsernameConfigured: true, smtpPasswordConfigured: true, feishuEnabled: false,
  feishuWebhookConfigured: true, feishuSecretConfigured: true }

beforeEach(() => {
  vi.mocked(request).mockReset()
  vi.mocked(putJsonWithCsrf).mockReset()
  vi.mocked(request).mockImplementation(async path =>
    path === '/api/notification-settings' ? saved : [])
})

describe('notification settings', () => {
  it('preserves existing credentials when blank and clears new secrets after saving', async () => {
    vi.mocked(putJsonWithCsrf).mockResolvedValue({ ...saved, emailEnabled: true })
    const wrapper = mount(NotificationPage)
    await flushPromises()
    const switches = wrapper.findAll('input[type="checkbox"]')
    await switches[0]!.setValue(true)
    await wrapper.get('input[autocomplete="new-password"]').setValue('replacement-password')
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(putJsonWithCsrf).toHaveBeenCalledWith('/api/notification-settings', expect.objectContaining({
      emailEnabled: true, smtpUsername: null, smtpPassword: 'replacement-password',
      feishuWebhook: null, feishuSecret: null,
    }))
    expect((wrapper.get('input[autocomplete="new-password"]').element as HTMLInputElement).value).toBe('')
    expect(wrapper.get('[role="status"]').text()).toContain('已保存')
    wrapper.unmount()
  })

  it('keeps both channels off and shows unknown delivery separately from success', async () => {
    vi.mocked(request).mockImplementation(async path => path === '/api/notification-settings' ? saved : [
      { id: 'delivery-1', runId: 'run-1', channel: 'EMAIL', status: 'UNKNOWN',
        errorCode: 'SMTP_SEND_UNKNOWN', createdAt: '2026-10-11T00:00:00Z' },
    ])
    vi.mocked(putJsonWithCsrf).mockResolvedValue(saved)
    const wrapper = mount(NotificationPage)
    await flushPromises()
    expect(wrapper.get('.history').text()).toContain('结果待核对')
    expect(wrapper.get('.history a').attributes('href')).toBe('/?run=run-1')
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(putJsonWithCsrf).toHaveBeenCalledWith('/api/notification-settings', expect.objectContaining({
      emailEnabled: false, feishuEnabled: false,
    }))
    wrapper.unmount()
  })

  it('keeps entered credentials available after a rejected save', async () => {
    vi.mocked(putJsonWithCsrf).mockRejectedValue(new HttpError(400, 'INVALID_NOTIFICATION_SETTINGS'))
    const wrapper = mount(NotificationPage)
    await flushPromises()
    await wrapper.get('input[autocomplete="new-password"]').setValue('retry-password')
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(wrapper.get('[role="alert"]').text()).toContain('核对')
    expect((wrapper.get('input[autocomplete="new-password"]').element as HTMLInputElement).value).toBe('retry-password')
    await wrapper.get('button.quiet').trigger('click')
    await flushPromises()
    expect((wrapper.get('input[autocomplete="new-password"]').element as HTMLInputElement).value).toBe('retry-password')
    wrapper.unmount()
  })
})
