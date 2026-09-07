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
    expect(call?.[0]).toBe('http://api.example.test/api/v1/admin/users?limit=50&offset=0')
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
    expect(call?.[1]).toMatchObject({ method: 'POST', body: JSON.stringify({ identifier: 'admin@example.com', password: 'password-value' }) })
  })

  it('sends the authenticated access credential and refresh token body when logging out', async () => {
    const fetchMock = vi.fn<(input: RequestInfo | URL, init?: RequestInit) => Promise<Response>>(() => Promise.resolve(jsonResponse({})))
    vi.stubGlobal('fetch', fetchMock)

    await expect(new CloudApiClient('http://api.example.test', 'access-value').logout('refresh-value')).resolves.toMatchObject({ kind: 'success' })

    const call = fetchMock.mock.calls[0]
    expect(call?.[0]).toBe('http://api.example.test/api/v1/auth/logout')
    expect(call?.[1]).toMatchObject({ method: 'POST', body: JSON.stringify({ refresh_token: 'refresh-value' }) })
    expect(((call?.[1] as RequestInit).headers as Headers).get('Authorization')).toBe('Bearer access-value')
  })

  it('encodes only documented user and audit list query parameters', async () => {
    const fetchMock = vi.fn<(input: RequestInfo | URL, init?: RequestInit) => Promise<Response>>()
      .mockResolvedValueOnce(jsonResponse({ users: [] }))
      .mockResolvedValueOnce(jsonResponse({ audit_logs: [] }))
    vi.stubGlobal('fetch', fetchMock)
    const client = new CloudApiClient('http://api.example.test', 'access-value')

    await client.listUsers({ q: 'admin+test@example.com & team', limit: 50, offset: 100 })
    await client.listAuditLogs({ limit: 50, offset: 150 })

    expect(fetchMock.mock.calls[0]?.[0]).toBe('http://api.example.test/api/v1/admin/users?q=admin%2Btest%40example.com+%26+team&limit=50&offset=100')
    expect(fetchMock.mock.calls[1]?.[0]).toBe('http://api.example.test/api/v1/admin/audit-logs?limit=50&offset=150')
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

  it('reads enabled and disabled setup status without credentials', async () => {
    const fetchMock = vi.fn<(input: RequestInfo | URL, init?: RequestInit) => Promise<Response>>()
      .mockResolvedValueOnce(jsonResponse({ enabled: false }))
      .mockResolvedValueOnce(jsonResponse({ enabled: true }))
    vi.stubGlobal('fetch', fetchMock)
    const client = new CloudApiClient('http://api.example.test', 'access-value')

    await expect(client.adminSetupStatus()).resolves.toEqual({ kind: 'success', data: { enabled: false }, requestId: 'request-1' })
    await expect(client.adminSetupStatus()).resolves.toEqual({ kind: 'success', data: { enabled: true }, requestId: 'request-1' })
    expect(((fetchMock.mock.calls[0]?.[1] as RequestInit).headers as Headers).get('Authorization')).toBeNull()
  })

  it('posts setup secrets only in the unauthenticated body', async () => {
    const fetchMock = vi.fn<(input: RequestInfo | URL, init?: RequestInit) => Promise<Response>>(() => Promise.resolve(jsonResponse({ status: 'configured' }, 201)))
    vi.stubGlobal('fetch', fetchMock)

    await expect(new CloudApiClient('http://api.example.test', 'access-value').completeAdminSetup('setup-token', 'admin_01', 'admin@example.test', 'password1')).resolves.toMatchObject({ kind: 'success' })
    const call = fetchMock.mock.calls[0]
    expect(call?.[0]).toBe('http://api.example.test/api/v1/admin/setup')
    expect(call?.[1]).toMatchObject({ method: 'POST', body: JSON.stringify({ setup_token: 'setup-token', username: 'admin_01', email: 'admin@example.test', password: 'password1' }) })
    expect(((call?.[1] as RequestInit).headers as Headers).get('Authorization')).toBeNull()
  })

  it('changes the administrator password with an authenticated body and accepts 204 without a body', async () => {
    const fetchMock = vi.fn<(input: RequestInfo | URL, init?: RequestInit) => Promise<Response>>(
      () => Promise.resolve(new Response(null, { status: 204, headers: { 'X-Request-ID': 'request-1' } })),
    )
    vi.stubGlobal('fetch', fetchMock)

    await expect(new CloudApiClient('http://api.example.test', 'access-value').changeAdminPassword('current-value', 'replacement-value')).resolves.toEqual({ kind: 'success', data: {}, requestId: 'request-1' })

    const call = fetchMock.mock.calls[0]
    expect(call?.[0]).toBe('http://api.example.test/api/v1/admin/password')
    expect(call?.[1]).toMatchObject({ method: 'POST', body: JSON.stringify({ current_password: 'current-value', new_password: 'replacement-value' }) })
    expect(((call?.[1] as RequestInit).headers as Headers).get('Authorization')).toBe('Bearer access-value')
    expect(((call?.[1] as RequestInit).headers as Headers).get('Content-Type')).toBe('application/json')
  })

  it('preserves the stable server error codes for a rejected password change', async () => {
    const fetchMock = vi.fn<(input: RequestInfo | URL, init?: RequestInit) => Promise<Response>>()
      .mockResolvedValueOnce(jsonResponse({ error: 'invalid_current_password' }, 403))
      .mockResolvedValueOnce(jsonResponse({ error: 'password_unchanged' }, 400))
      .mockResolvedValueOnce(jsonResponse({ error: 'invalid_request' }, 400))
    vi.stubGlobal('fetch', fetchMock)
    const client = new CloudApiClient('http://api.example.test', 'access-value')

    await expect(client.changeAdminPassword('wrong', 'replacement-value')).resolves.toEqual({ kind: 'forbidden', status: 403, error: 'invalid_current_password', requestId: 'request-1' })
    await expect(client.changeAdminPassword('current-value', 'current-value')).resolves.toEqual({ kind: 'error', status: 400, error: 'password_unchanged', requestId: 'request-1' })
    await expect(client.changeAdminPassword('current-value', 'short')).resolves.toEqual({ kind: 'error', status: 400, error: 'invalid_request', requestId: 'request-1' })
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
