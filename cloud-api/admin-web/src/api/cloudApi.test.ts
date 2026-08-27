import { afterEach, describe, expect, it, vi } from 'vitest'
import { CloudApiClient } from './cloudApi'

function jsonResponse(body: unknown, status = 200, requestId = 'request-1'): Response {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json', 'X-Request-ID': requestId } })
}

afterEach(() => vi.unstubAllGlobals())

describe('CloudApiClient', () => {
  it('sends an administrator access credential and accepts only the documented candidate user shape', async () => {
    const fetchMock = vi.fn<(input: RequestInfo | URL, init?: RequestInit) => Promise<Response>>(
      () => Promise.resolve(jsonResponse({ users: [{ id: 'u-1', email: 'admin@example.com', role: 'admin', created_at: '2026-01-02T03:04:05Z' }] })),
    )
    vi.stubGlobal('fetch', fetchMock)

    const result = await new CloudApiClient('http://api.example.test/', 'access-value').listUsers()

    expect(result).toEqual({ kind: 'success', data: [{ id: 'u-1', email: 'admin@example.com', role: 'admin', created_at: '2026-01-02T03:04:05Z' }], requestId: 'request-1' })
    const call = fetchMock.mock.calls[0]
    expect(call?.[0]).toBe('http://api.example.test/api/v1/admin/users')
    expect(((call?.[1] as RequestInit).headers as Headers).get('Authorization')).toBe('Bearer access-value')
  })

  it('posts credentials only in the request body and parses a complete login response', async () => {
    const fetchMock = vi.fn<(input: RequestInfo | URL, init?: RequestInit) => Promise<Response>>(() => Promise.resolve(jsonResponse({ access_token: 'access-value', refresh_token: 'refresh-value', token_type: 'Bearer', expires_in: 900 })))
    vi.stubGlobal('fetch', fetchMock)

    await expect(new CloudApiClient('http://api.example.test').login('admin@example.com', 'password-value')).resolves.toEqual({
      kind: 'success', data: { accessToken: 'access-value', refreshToken: 'refresh-value', tokenType: 'Bearer', expiresIn: 900 }, requestId: 'request-1',
    })
    const call = fetchMock.mock.calls[0]
    expect(call?.[0]).toBe('http://api.example.test/api/v1/auth/login')
    expect(call?.[1]).toMatchObject({ method: 'POST', body: JSON.stringify({ email: 'admin@example.com', password: 'password-value' }) })
  })

  it('refreshes once after an expired access credential and retries the original request with the replacement', async () => {
    const refreshAccess = vi.fn(async () => 'rotated-access')
    const fetchMock = vi.fn<(input: RequestInfo | URL, init?: RequestInit) => Promise<Response>>()
      .mockResolvedValueOnce(jsonResponse({ error: 'expired' }, 401))
      .mockResolvedValueOnce(jsonResponse({ users: [{ id: 'u-1', email: 'admin@example.com', role: 'admin', created_at: '2026-01-02T03:04:05Z' }] }))
    vi.stubGlobal('fetch', fetchMock)

    await expect(new CloudApiClient('http://api.example.test', 'expired-access', refreshAccess).listUsers()).resolves.toMatchObject({ kind: 'success' })
    expect(refreshAccess).toHaveBeenCalledTimes(1)
    expect((((fetchMock.mock.calls[1]?.[1] as RequestInit).headers) as Headers).get('Authorization')).toBe('Bearer rotated-access')
  })

  it('does not retry more than once and reports an expired session', async () => {
    const refreshAccess = vi.fn(async () => 'rotated-access')
    const authenticationFailure = vi.fn()
    vi.stubGlobal('fetch', vi.fn(() => Promise.resolve(jsonResponse({ error: 'expired' }, 401))))

    await expect(new CloudApiClient('http://api.example.test', 'access-value', refreshAccess, authenticationFailure).listUsers()).resolves.toMatchObject({ kind: 'unauthorized' })
    expect(refreshAccess).toHaveBeenCalledTimes(1)
    expect(authenticationFailure).toHaveBeenCalledTimes(1)
  })

  it('holds 501 routes in a controlled unavailable state', async () => {
    vi.stubGlobal('fetch', vi.fn(() => Promise.resolve(jsonResponse({ error: 'not_implemented', request_id: 'body-id' }, 501))))
    await expect(new CloudApiClient('http://api.example.test').listAuditLogs()).resolves.toEqual({ kind: 'unavailable', status: 501, error: 'not_implemented', requestId: 'request-1' })
  })

  it('rejects an undeclared response envelope instead of displaying partial data', async () => {
    vi.stubGlobal('fetch', vi.fn(() => Promise.resolve(jsonResponse({ items: [] }))))
    await expect(new CloudApiClient('http://api.example.test').listUsers()).resolves.toEqual({ kind: 'error', status: 200, error: 'invalid_response_contract', requestId: 'request-1' })
  })
})
