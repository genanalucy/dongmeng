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
  readonly username?: string
  readonly email?: string
  readonly role: string
  readonly created_at: string
  readonly disabled_at?: string
}

export interface Entitlement {
  readonly id: string
  readonly kind: string
  readonly starts_at: string
  readonly expires_at: string
  readonly revoked_at?: string
}

export interface TranslationSession {
  readonly id: string
  readonly install_id: string
  readonly created_at: string
  readonly expires_at: string
  readonly ended_at?: string
  readonly revoked_at?: string
  readonly termination_reason?: string
}

export interface UsageRecord {
  readonly id: string
  readonly session_id: string
  readonly audio_seconds: number
  readonly characters: number
  readonly created_at: string
}

export interface CodeBatch {
  readonly id: string
  readonly name: string
  readonly duration_days: number
  readonly created_at: string
  readonly disabled_at?: string
  readonly total_codes: number
  readonly redeemed_codes: number
  readonly unredeemed_codes: number
}

export interface CreatedCodeBatch {
  readonly batch: CodeBatch
  readonly codes: readonly string[]
}

export interface CurrentUser {
  readonly id: string
  readonly username?: string
  readonly email?: string
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
  const username = typeof value.username === 'string' && value.username !== '' ? value.username : undefined
  const email = typeof value.email === 'string' && value.email !== '' ? value.email : undefined
  const role = requiredString(value, 'role')
  const createdAt = requiredString(value, 'created_at')
  return id === null || role === null || createdAt === null || (username === undefined && email === undefined) ? null : { id, ...(username === undefined ? {} : { username }), ...(email === undefined ? {} : { email }), role, created_at: createdAt, ...(optionalString(value, 'disabled_at') === undefined ? {} : { disabled_at: optionalString(value, 'disabled_at') }) }
}

function parseCurrentUser(value: unknown): CurrentUser | null {
  if (!isRecord(value)) return null
  const id = requiredString(value, 'id')
  const username = optionalString(value, 'username')
  const email = optionalString(value, 'email')
  const role = requiredString(value, 'role')
  return id === null || role === null || (username === undefined && email === undefined) ? null : { id, ...(username === undefined ? {} : { username }), ...(email === undefined ? {} : { email }), role }
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

function optionalString(record: Record<string, unknown>, field: string): string | undefined {
  return typeof record[field] === 'string' && record[field] !== '' ? record[field] as string : undefined
}

function requiredNumber(record: Record<string, unknown>, field: string): number | null {
  return typeof record[field] === 'number' && Number.isFinite(record[field]) ? record[field] as number : null
}

function parseEntitlement(value: unknown): Entitlement | null {
  if (!isRecord(value)) return null
  const id = requiredString(value, 'id'), kind = requiredString(value, 'kind'), startsAt = requiredString(value, 'starts_at'), expiresAt = requiredString(value, 'expires_at')
  return id === null || kind === null || startsAt === null || expiresAt === null ? null : { id, kind, starts_at: startsAt, expires_at: expiresAt, ...(optionalString(value, 'revoked_at') === undefined ? {} : { revoked_at: optionalString(value, 'revoked_at') }) }
}

function parseTranslationSession(value: unknown): TranslationSession | null {
  if (!isRecord(value)) return null
  const id = requiredString(value, 'id'), installId = requiredString(value, 'install_id'), createdAt = requiredString(value, 'created_at'), expiresAt = requiredString(value, 'expires_at')
  if (id === null || installId === null || createdAt === null || expiresAt === null) return null
  return { id, install_id: installId, created_at: createdAt, expires_at: expiresAt, ...(optionalString(value, 'ended_at') === undefined ? {} : { ended_at: optionalString(value, 'ended_at') }), ...(optionalString(value, 'revoked_at') === undefined ? {} : { revoked_at: optionalString(value, 'revoked_at') }), ...(optionalString(value, 'termination_reason') === undefined ? {} : { termination_reason: optionalString(value, 'termination_reason') }) }
}

function parseUsageRecord(value: unknown): UsageRecord | null {
  if (!isRecord(value)) return null
  const id = requiredString(value, 'id'), sessionId = requiredString(value, 'session_id'), audioSeconds = requiredNumber(value, 'audio_seconds'), characters = requiredNumber(value, 'characters'), createdAt = requiredString(value, 'created_at')
  return id === null || sessionId === null || audioSeconds === null || characters === null || createdAt === null ? null : { id, session_id: sessionId, audio_seconds: audioSeconds, characters, created_at: createdAt }
}

function parseCodeBatch(value: unknown): CodeBatch | null {
  if (!isRecord(value)) return null
  const id = requiredString(value, 'id'), name = requiredString(value, 'name'), durationDays = requiredNumber(value, 'duration_days'), createdAt = requiredString(value, 'created_at'), total = requiredNumber(value, 'total_codes'), redeemed = requiredNumber(value, 'redeemed_codes'), unredeemed = requiredNumber(value, 'unredeemed_codes')
  return id === null || name === null || durationDays === null || createdAt === null || total === null || redeemed === null || unredeemed === null ? null : { id, name, duration_days: durationDays, created_at: createdAt, total_codes: total, redeemed_codes: redeemed, unredeemed_codes: unredeemed, ...(optionalString(value, 'disabled_at') === undefined ? {} : { disabled_at: optionalString(value, 'disabled_at') }) }
}

function parseCreatedCodeBatch(value: unknown): CreatedCodeBatch | null {
  if (!isRecord(value)) return null
  const batch = parseCodeBatch(value.batch)
  const codes = Array.isArray(value.codes) && value.codes.every((code): code is string => typeof code === 'string' && code !== '') ? value.codes : null
  return batch === null || codes === null ? null : { batch, codes }
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

  private post<T>(path: string, body: Readonly<Record<string, unknown>>, parse: (value: unknown) => T | null, authenticated = false): Promise<ApiResult<T>> {
    return this.request(path, { method: 'POST', body: JSON.stringify(body) }, parse, true, authenticated)
  }

  login(identifier: string, password: string): Promise<ApiResult<AuthTokens>> {
    return this.post('/api/v1/auth/login', { identifier, password }, parseAuthTokens)
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

  listEntitlements(userId: string): Promise<ApiResult<readonly Entitlement[]>> {
    return this.get(`/api/v1/admin/users/${userId}/entitlements?limit=100&offset=0`, parseArrayEnvelope('entitlements', parseEntitlement))
  }

  listTranslationSessions(userId: string): Promise<ApiResult<readonly TranslationSession[]>> {
    return this.get(`/api/v1/admin/users/${userId}/translation-sessions?limit=100&offset=0`, parseArrayEnvelope('translation_sessions', parseTranslationSession))
  }

  listUsageRecords(userId: string): Promise<ApiResult<readonly UsageRecord[]>> {
    return this.get(`/api/v1/admin/users/${userId}/usage-records?limit=100&offset=0`, parseArrayEnvelope('usage_records', parseUsageRecord))
  }

  grantEntitlement(userId: string): Promise<ApiResult<Entitlement>> {
    return this.post(`/api/v1/admin/users/${userId}/entitlements`, {}, parseEntitlement, true)
  }

  revokeEntitlement(userId: string, entitlementId: string): Promise<ApiResult<Record<string, never>>> {
    return this.post(`/api/v1/admin/users/${userId}/entitlements/${entitlementId}/revoke`, {}, () => ({}), true)
  }

  disableUser(userId: string): Promise<ApiResult<Record<string, never>>> {
    return this.post(`/api/v1/admin/users/${userId}/disable`, {}, () => ({}), true)
  }

  revokeTranslationSession(userId: string, sessionId: string): Promise<ApiResult<Record<string, never>>> {
    return this.post(`/api/v1/admin/users/${userId}/translation-sessions/${sessionId}/revoke`, {}, () => ({}), true)
  }

  listCodeBatches(): Promise<ApiResult<readonly CodeBatch[]>> {
    return this.get('/api/v1/admin/code-batches?limit=100&offset=0', parseArrayEnvelope('code_batches', parseCodeBatch))
  }

  createCodeBatch(name: string, count: number): Promise<ApiResult<CreatedCodeBatch>> {
    return this.post('/api/v1/admin/code-batches', { name, count }, parseCreatedCodeBatch, true)
  }

  disableCodeBatch(batchId: string): Promise<ApiResult<Record<string, never>>> {
    return this.post(`/api/v1/admin/code-batches/${batchId}/disable`, {}, () => ({}), true)
  }
}

export function apiErrorMessage<T>(result: Exclude<ApiResult<T>, { readonly kind: 'success' }>): string { return errorMessage(result.error, result.requestId) }
