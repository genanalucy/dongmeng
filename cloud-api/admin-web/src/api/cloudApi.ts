export interface ServiceConfig {
  readonly environment: string
  readonly service: string
  readonly version: string
}

export interface ProbeResult {
  readonly status: 'ok' | 'failed'
  readonly label: string
  readonly detail: string
}

export interface AdminUser {
  readonly id: string
  readonly email: string
  readonly role: string
  readonly created_at: string
}

export interface CurrentUser {
  readonly id: string
  readonly email: string
  readonly role: string
}

export interface AuthTokens {
  readonly accessToken: string
  readonly refreshToken: string
  readonly tokenType: string
  readonly expiresIn: number
}

export interface Pagination {
  readonly limit: number
  readonly offset: number
}

export interface UserListQuery extends Pagination {
  readonly q?: string
}

export type AuditListQuery = Pagination

export interface AuditLog {
  readonly id: string
  readonly admin_id: string
  readonly action: string
  readonly target_type: string
  readonly target_id?: string
  readonly metadata: Readonly<Record<string, unknown>>
  readonly created_at: string
}

interface ApiErrorBody {
  readonly error?: string
  readonly request_id?: string
}

interface AuthTokenResponse {
  readonly access_token?: unknown
  readonly refresh_token?: unknown
  readonly token_type?: unknown
  readonly expires_in?: unknown
}

export type ApiResult<T> =
  | { readonly kind: 'success'; readonly data: T; readonly requestId: string | null }
  | { readonly kind: 'unavailable'; readonly status: 404 | 501; readonly error: string; readonly requestId: string | null }
  | { readonly kind: 'unauthorized'; readonly status: 401; readonly error: string; readonly requestId: string | null }
  | { readonly kind: 'forbidden'; readonly status: 403; readonly error: string; readonly requestId: string | null }
  | { readonly kind: 'error'; readonly status: number | null; readonly error: string; readonly requestId: string | null }

export class CloudApiError extends Error {
  readonly status: number | null
  readonly requestId: string | null

  constructor(message: string, status: number | null, requestId: string | null) {
    super(message)
    this.name = 'CloudApiError'
    this.status = status
    this.requestId = requestId
  }
}

function endpoint(baseUrl: string, path: string): string {
  return `${baseUrl.replace(/\/$/, '')}${path}`
}

function errorMessage(error: string, requestId: string | null): string {
  return requestId === null ? `请求失败：${error}` : `请求失败：${error}（请求 ID：${requestId}）`
}

function headersFor(token: string, hasJsonBody = false): Headers {
  const headers = new Headers({ Accept: 'application/json' })
  if (hasJsonBody) headers.set('Content-Type', 'application/json')
  if (token !== '') headers.set('Authorization', `Bearer ${token}`)
  return headers
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
}

function requiredString(record: Record<string, unknown>, field: string): string | null {
  const value = record[field]
  return typeof value === 'string' && value !== '' ? value : null
}

function parseAdminUser(value: unknown): AdminUser | null {
  if (!isRecord(value)) return null
  const id = requiredString(value, 'id')
  const email = requiredString(value, 'email')
  const role = requiredString(value, 'role')
  const createdAt = requiredString(value, 'created_at')
  return id === null || email === null || role === null || createdAt === null ? null : { id, email, role, created_at: createdAt }
}

function parseCurrentUser(value: unknown): CurrentUser | null {
  if (!isRecord(value)) return null
  const id = requiredString(value, 'id')
  const email = requiredString(value, 'email')
  const role = requiredString(value, 'role')
  return id === null || email === null || role === null ? null : { id, email, role }
}

function parseAuthTokens(value: unknown): AuthTokens | null {
  if (!isRecord(value)) return null
  const response = value as AuthTokenResponse
  return typeof response.access_token === 'string' && response.access_token !== ''
    && typeof response.refresh_token === 'string' && response.refresh_token !== ''
    && typeof response.token_type === 'string' && response.token_type !== ''
    && typeof response.expires_in === 'number' && Number.isFinite(response.expires_in) && response.expires_in > 0
    ? { accessToken: response.access_token, refreshToken: response.refresh_token, tokenType: response.token_type, expiresIn: response.expires_in }
    : null
}

function parseAuditLog(value: unknown): AuditLog | null {
  if (!isRecord(value)) return null
  const id = requiredString(value, 'id')
  const adminId = requiredString(value, 'admin_id')
  const action = requiredString(value, 'action')
  const targetType = requiredString(value, 'target_type')
  const createdAt = requiredString(value, 'created_at')
  const targetId = value.target_id
  if (id === null || adminId === null || action === null || targetType === null || createdAt === null || (targetId !== undefined && typeof targetId !== 'string') || !isRecord(value.metadata)) return null
  return { id, admin_id: adminId, action, target_type: targetType, ...(targetId === undefined ? {} : { target_id: targetId }), metadata: value.metadata, created_at: createdAt }
}

function parseArray<T>(value: unknown, parseItem: (item: unknown) => T | null): readonly T[] | null {
  if (!Array.isArray(value)) return null
  const items = value.map(parseItem)
  return items.every((item): item is T => item !== null) ? items : null
}

function parseArrayEnvelope<T>(field: string, parseItem: (item: unknown) => T | null): (value: unknown) => readonly T[] | null {
  return (value) => isRecord(value) ? parseArray(value[field], parseItem) : null
}

export class CloudApiClient {
  readonly baseUrl: string
  private accessToken: string
  private readonly refreshAccess: (() => Promise<string | null>) | undefined
  private readonly onAuthenticationFailure: (() => void) | undefined

  constructor(baseUrl: string, accessToken = '', refreshAccess?: () => Promise<string | null>, onAuthenticationFailure?: () => void) {
    this.baseUrl = baseUrl
    this.accessToken = accessToken
    this.refreshAccess = refreshAccess
    this.onAuthenticationFailure = onAuthenticationFailure
  }

  private async request<T>(path: string, init: RequestInit, parse: (body: unknown) => T | null, retryAfterRefresh: boolean, authenticated: boolean): Promise<ApiResult<T>> {
    let response: Response
    try {
      response = await fetch(endpoint(this.baseUrl, path), {
        ...init,
        headers: headersFor(authenticated ? this.accessToken : '', init.body !== undefined),
      })
    } catch (reason) {
      return { kind: 'error', status: null, error: reason instanceof Error ? reason.message : '无法连接 Cloud API。', requestId: null }
    }

    const body = await response.json().catch((): unknown => null)
    const errorBody = isRecord(body) ? body as ApiErrorBody : {}
    const requestId = response.headers.get('X-Request-ID') ?? errorBody.request_id ?? null
    if (response.status === 401 && authenticated && retryAfterRefresh && this.refreshAccess !== undefined) {
      const refreshedAccessToken = await this.refreshAccess()
      if (refreshedAccessToken !== null) {
        this.accessToken = refreshedAccessToken
        return this.request(path, init, parse, false, authenticated)
      }
    }
    if (!response.ok) {
      const error = errorBody.error ?? (response.statusText || 'unknown_error')
      if (response.status === 401) {
        if (authenticated) this.onAuthenticationFailure?.()
        return { kind: 'unauthorized', status: 401, error, requestId }
      }
      if (response.status === 403) return { kind: 'forbidden', status: 403, error, requestId }
      if (response.status === 404 || response.status === 501) return { kind: 'unavailable', status: response.status, error, requestId }
      return { kind: 'error', status: response.status, error, requestId }
    }

    const data = parse(body)
    return data === null
      ? { kind: 'error', status: response.status, error: 'invalid_response_contract', requestId }
      : { kind: 'success', data, requestId }
  }

  private get<T>(path: string, parse: (body: unknown) => T | null, authenticated = true): Promise<ApiResult<T>> {
    return this.request(path, { method: 'GET' }, parse, true, authenticated)
  }

  private post<T>(path: string, body: Readonly<Record<string, string>>, parse: (value: unknown) => T | null, authenticated = false): Promise<ApiResult<T>> {
    return this.request(path, { method: 'POST', body: JSON.stringify(body) }, parse, true, authenticated)
  }

  login(email: string, password: string): Promise<ApiResult<AuthTokens>> {
    return this.post('/api/v1/auth/login', { email, password }, parseAuthTokens)
  }

  refresh(refreshToken: string): Promise<ApiResult<AuthTokens>> {
    return this.post('/api/v1/auth/refresh', { refresh_token: refreshToken }, parseAuthTokens)
  }

  logout(refreshToken: string): Promise<ApiResult<Record<string, never>>> {
    return this.post('/api/v1/auth/logout', { refresh_token: refreshToken }, (body) => isRecord(body) ? {} : {}, true)
  }

  me(): Promise<ApiResult<CurrentUser>> {
    return this.get('/api/v1/users/me', parseCurrentUser)
  }

  async checkPlatform(): Promise<{ readonly health: ProbeResult; readonly ready: ProbeResult; readonly config: ServiceConfig }> {
    const [healthResult, readyResult, configResult] = await Promise.all([
      this.get('/api/v1/health', (body): { readonly status: string } | null => isRecord(body) && typeof body.status === 'string' ? { status: body.status } : null, false),
      this.get('/api/v1/ready', (body): { readonly status: string } | null => isRecord(body) && typeof body.status === 'string' ? { status: body.status } : null, false),
      this.get('/api/v1/config', (body): ServiceConfig | null => {
        if (!isRecord(body)) return null
        const environment = requiredString(body, 'environment')
        const service = requiredString(body, 'service')
        const version = requiredString(body, 'version')
        return environment === null || service === null || version === null ? null : { environment, service, version }
      }, false),
    ])

    if (configResult.kind !== 'success') throw new CloudApiError(errorMessage(configResult.error, configResult.requestId), configResult.status, configResult.requestId)

    const toProbe = (result: ApiResult<{ readonly status: string }>, expected: string, label: string): ProbeResult => result.kind === 'success' && result.data.status === expected
      ? { status: 'ok', label, detail: expected === 'ready' ? 'PostgreSQL 就绪' : '服务存活' }
      : { status: 'failed', label, detail: result.kind === 'success' ? '未获得有效响应' : errorMessage(result.error, result.requestId) }

    return { health: toProbe(healthResult, 'ok', '存活探针'), ready: toProbe(readyResult, 'ready', '就绪探针'), config: configResult.data }
  }

  listUsers(query: UserListQuery = { limit: 50, offset: 0 }): Promise<ApiResult<readonly AdminUser[]>> {
    const parameters = new URLSearchParams()
    if (query.q !== undefined && query.q !== '') parameters.set('q', query.q)
    parameters.set('limit', String(query.limit))
    parameters.set('offset', String(query.offset))
    return this.get(`/api/v1/admin/users?${parameters.toString()}`, parseArrayEnvelope('users', parseAdminUser))
  }

  listAuditLogs(query: AuditListQuery = { limit: 50, offset: 0 }): Promise<ApiResult<readonly AuditLog[]>> {
    const parameters = new URLSearchParams({ limit: String(query.limit), offset: String(query.offset) })
    return this.get(`/api/v1/admin/audit-logs?${parameters.toString()}`, parseArrayEnvelope('audit_logs', parseAuditLog))
  }
}

export function apiErrorMessage<T>(result: Exclude<ApiResult<T>, { readonly kind: 'success' }>): string { return errorMessage(result.error, result.requestId) }
