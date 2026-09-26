import { afterEach, expect, it, vi } from 'vitest'
import { request } from './http'

afterEach(() => { vi.unstubAllGlobals(); vi.useRealTimers() })

it('times out while an HTTP response body is stalled, not only before headers', async () => {
  vi.useFakeTimers()
  vi.stubGlobal('fetch', vi.fn((_path, options: RequestInit) => Promise.resolve(new Response(
    new ReadableStream({
      start(controller) {
        controller.enqueue(new TextEncoder().encode('{"id":'))
        options.signal?.addEventListener('abort', () => controller.error(new DOMException('Aborted', 'AbortError')))
      },
    }),
  ))))
  const result = request('/api/auth/me').then(() => 'resolved', () => 'aborted')
  await vi.advanceTimersByTimeAsync(10_000)
  expect(await Promise.race([result, Promise.resolve('still-pending')])).toBe('aborted')
})
