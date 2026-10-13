import { afterEach, expect, it, vi } from 'vitest'
import { HttpError, request } from './http'

afterEach(() => { vi.unstubAllGlobals(); vi.useRealTimers() })

it('preserves rejected CSV rows so confirmation errors can be displayed', async () => {
  const preview = { canImport: false, rows: [{ rowNumber: 2, status: 'ERROR', errorCode: 'SNAPSHOT_CONFLICT', data: null }] }
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(JSON.stringify(preview), { status: 422 })))
  const error = await request('/api/metrics/import/confirm').catch(cause => cause)
  expect(error).toBeInstanceOf(HttpError)
  if (!(error instanceof HttpError)) throw new Error('Expected HTTP rejection')
  expect(error.body).toEqual(preview)
})

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
