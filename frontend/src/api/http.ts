export class HttpError extends Error {
  constructor(readonly status: number) { super(`HTTP ${status}`) }
}

export async function request(path: string, options: RequestInit = {}): Promise<unknown> {
  const controller = new AbortController()
  const timeout = setTimeout(() => controller.abort(), 10_000)
  try {
    const response = await fetch(path, { ...options, credentials: 'same-origin', signal: controller.signal })
    if (!response.ok) throw new HttpError(response.status)
    return response.status === 204 ? null : await response.json()
  } finally {
    clearTimeout(timeout)
  }
}

export async function postWithCsrf(path: string, body = ''): Promise<void> {
  const csrf = await request('/api/auth/csrf')
  if (typeof csrf !== 'object' || csrf === null || !('headerName' in csrf) || !('token' in csrf)
      || typeof csrf.headerName !== 'string' || typeof csrf.token !== 'string') {
    throw new Error('Invalid CSRF response')
  }
  await request(path, {
    method: 'POST',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded', [csrf.headerName]: csrf.token },
    body,
  })
}
