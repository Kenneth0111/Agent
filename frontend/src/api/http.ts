export class HttpError extends Error {
  constructor(readonly status: number, readonly code?: string) { super(code ?? `HTTP ${status}`) }
}

export async function request(path: string, options: RequestInit = {}, timeoutMs = 10_000): Promise<unknown> {
  const controller = new AbortController()
  const timeout = setTimeout(() => controller.abort(), timeoutMs)
  try {
    const response = await fetch(path, { ...options, credentials: 'same-origin', signal: controller.signal })
    if (response.status === 204) return null
    if (!response.ok) {
      try {
        const body: unknown = await response.json()
        const code = typeof body === 'object' && body !== null && 'code' in body && typeof body.code === 'string'
          ? body.code : undefined
        throw new HttpError(response.status, code)
      } catch (cause) {
        if (cause instanceof HttpError) throw cause
        throw new HttpError(response.status)
      }
    }
    return await response.json()
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

export async function postJsonWithCsrf(path: string, body: unknown, timeoutMs = 10_000): Promise<unknown> {
  return jsonWithCsrf('POST', path, body, timeoutMs)
}

export async function postMultipartWithCsrf(path: string, body: FormData): Promise<unknown> {
  const csrf = await request('/api/auth/csrf')
  if (typeof csrf !== 'object' || csrf === null || !('headerName' in csrf) || !('token' in csrf)
      || typeof csrf.headerName !== 'string' || typeof csrf.token !== 'string') {
    throw new Error('Invalid CSRF response')
  }
  return request(path, { method: 'POST', headers: { [csrf.headerName]: csrf.token }, body })
}

export async function putJsonWithCsrf(path: string, body: unknown): Promise<unknown> {
  return jsonWithCsrf('PUT', path, body)
}

export async function deleteWithCsrf(path: string): Promise<void> {
  const csrf = await request('/api/auth/csrf')
  if (typeof csrf !== 'object' || csrf === null || !('headerName' in csrf) || !('token' in csrf)
      || typeof csrf.headerName !== 'string' || typeof csrf.token !== 'string') {
    throw new Error('Invalid CSRF response')
  }
  await request(path, { method: 'DELETE', headers: { [csrf.headerName]: csrf.token } })
}

async function jsonWithCsrf(method: 'POST' | 'PUT', path: string, body: unknown, timeoutMs = 10_000): Promise<unknown> {
  const csrf = await request('/api/auth/csrf')
  if (typeof csrf !== 'object' || csrf === null || !('headerName' in csrf) || !('token' in csrf)
      || typeof csrf.headerName !== 'string' || typeof csrf.token !== 'string') {
    throw new Error('Invalid CSRF response')
  }
  return request(path, {
    method,
    headers: { 'Content-Type': 'application/json', [csrf.headerName]: csrf.token },
    body: JSON.stringify(body),
  }, timeoutMs)
}
