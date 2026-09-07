import { useCallback, useEffect, useMemo, useState, type FormEvent, type ReactElement } from 'react'
import { clearAdminSession, loadAdminSession, saveAdminSession } from './api/adminSession'
import {
  apiErrorMessage,
  CloudApiClient,
  type AdminUser,
  type ApiResult,
  type AuditLog,
  type AuthTokens,
  type CodeBatch,
  type CurrentUser,
  type Entitlement,
  type ProbeResult,
  type ServiceConfig,
  type TranslationSession,
  type UsageRecord,
} from './api/cloudApi'
import { AdminListPanel } from './components/AdminListPanel'
import { AdminLogin } from './components/AdminLogin'
import { AdminSetup } from './components/AdminSetup'

type Page = 'overview' | 'users' | 'codes' | 'audit'
type PlatformState = { readonly health: ProbeResult; readonly ready: ProbeResult; readonly config: ServiceConfig }
type AuthenticationState = 'checking' | 'anonymous' | 'authenticated'
type FailureResult<T> = Exclude<ApiResult<T>, { readonly kind: 'success' }>

const navigation: ReadonlyArray<{ readonly page: Page; readonly label: string }> = [
  { page: 'overview', label: '概览' },
  { page: 'users', label: '用户' },
  { page: 'codes', label: '兑换码' },
  { page: 'audit', label: '审计' },
]

function defaultBaseUrl(): string {
  if (typeof window === 'undefined') return 'http://127.0.0.1:8080'
  return import.meta.env.DEV ? 'http://127.0.0.1:8080' : window.location.origin
}

function readableTime(value: string | undefined): string {
  if (value === undefined) return '—'
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? value : new Intl.DateTimeFormat('zh-CN', { dateStyle: 'medium', timeStyle: 'short' }).format(date)
}

function shortId(value: string): string {
  return value.length <= 12 ? value : `${value.slice(0, 8)}…${value.slice(-4)}`
}

function failureMessage<T>(result: FailureResult<T>): string {
  if (result.kind === 'forbidden') return '当前管理员无权执行此操作。'
  if (result.kind === 'unauthorized') return '管理员登录状态已失效。'
  return apiErrorMessage(result)
}

function MetricCard({ label, value, detail }: { readonly label: string; readonly value: string; readonly detail: string }): ReactElement {
  return <article className="metric-card"><p>{label}</p><strong>{value}</strong><span>{detail}</span></article>
}

function StatusRow({ result }: { readonly result: ProbeResult }): ReactElement {
  return <li><span className={`status-dot ${result.status}`} aria-hidden="true" /><span><strong>{result.label}</strong><small>{result.detail}</small></span><span className={`status-label ${result.status}`}>{result.status === 'ok' ? '正常' : '异常'}</span></li>
}

interface UserDetailData {
  readonly entitlements: readonly Entitlement[]
  readonly sessions: readonly TranslationSession[]
  readonly usage: readonly UsageRecord[]
}

function UserDetail({ client, user, onBack }: { readonly client: CloudApiClient; readonly user: AdminUser; readonly onBack: () => void }): ReactElement {
  const [data, setData] = useState<UserDetailData | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)

  const load = useCallback(async (): Promise<void> => {
    setError(null)
    const [entitlements, sessions, usage] = await Promise.all([
      client.listEntitlements(user.id), client.listTranslationSessions(user.id), client.listUsageRecords(user.id),
    ])
    if (entitlements.kind !== 'success') return setError(failureMessage(entitlements))
    if (sessions.kind !== 'success') return setError(failureMessage(sessions))
    if (usage.kind !== 'success') return setError(failureMessage(usage))
    setData({ entitlements: entitlements.data, sessions: sessions.data, usage: usage.data })
  }, [client, user.id])

  useEffect(() => {
    const timer = window.setTimeout(() => { void load() }, 0)
    return () => window.clearTimeout(timer)
  }, [load])

  const mutate = async (request: () => Promise<ApiResult<unknown>>): Promise<void> => {
    setBusy(true)
    setError(null)
    try {
      const result = await request()
      if (result.kind !== 'success') setError(failureMessage(result))
      else await load()
    } catch {
      setError('操作失败，请检查网络后重试。')
    } finally {
      setBusy(false)
    }
  }

  const identity = user.username ?? user.email ?? shortId(user.id)
  return <section className="detail-page">
    <div className="page-heading"><div><p className="eyebrow">用户详情</p><h1>{identity}</h1><p>敏感身份与设备标识均已最小化显示。</p></div><button className="secondary-button" onClick={onBack} type="button">返回用户列表</button></div>
    {error === null ? null : <div className="error-banner" role="alert">{error}</div>}
    <section className="panel action-strip"><div><strong>{user.disabled_at === undefined ? user.role : '已禁用'}</strong><small>{user.email ?? '未显示邮箱'} · {shortId(user.id)}</small></div><div><button className="primary-button" disabled={busy || user.disabled_at !== undefined} onClick={() => void mutate(() => client.grantEntitlement(user.id))} type="button">发放一年权益</button><button className="danger-button" disabled={busy || user.disabled_at !== undefined || user.role === 'admin'} onClick={() => { if (window.confirm(`确认禁用用户 ${identity}？该操作会终止活跃会话。`)) void mutate(() => client.disableUser(user.id)) }} type="button">禁用用户</button></div></section>
    {data === null ? <p className="empty-state" role="status">正在读取用户运营数据…</p> : <>
      <section className="panel resource-section"><div className="panel-heading"><h2>权益（最近 100 条）</h2><span>{data.entitlements.length} 条</span></div><div className="table-wrap"><table><thead><tr><th>类型</th><th>开始</th><th>到期</th><th>状态</th><th>操作</th></tr></thead><tbody>{data.entitlements.map((item) => <tr key={item.id}><td>{item.kind}</td><td>{readableTime(item.starts_at)}</td><td>{readableTime(item.expires_at)}</td><td>{item.revoked_at === undefined ? '有效/按时间生效' : '已撤销'}</td><td>{item.revoked_at === undefined ? <button className="text-danger" disabled={busy} onClick={() => { if (window.confirm('确认撤销该权益？关联翻译会话将终止。')) void mutate(() => client.revokeEntitlement(user.id, item.id)) }} type="button">撤销</button> : '—'}</td></tr>)}</tbody></table></div></section>
      <section className="panel resource-section"><div className="panel-heading"><h2>翻译会话（最近 100 条）</h2><span>{data.sessions.length} 条</span></div><div className="table-wrap"><table><thead><tr><th>会话</th><th>安装</th><th>创建</th><th>状态</th><th>操作</th></tr></thead><tbody>{data.sessions.map((item) => { const terminal = item.ended_at !== undefined || item.revoked_at !== undefined; return <tr key={item.id}><td><code>{shortId(item.id)}</code></td><td><code>{shortId(item.install_id)}</code></td><td>{readableTime(item.created_at)}</td><td>{item.termination_reason ?? (terminal ? '已结束' : '活跃')}</td><td>{terminal ? '—' : <button className="text-danger" disabled={busy} onClick={() => { if (window.confirm('确认强制终止该翻译会话？')) void mutate(() => client.revokeTranslationSession(user.id, item.id)) }} type="button">终止</button>}</td></tr> })}</tbody></table></div></section>
      <section className="panel resource-section"><div className="panel-heading"><h2>使用量（最近 100 条）</h2><span>{data.usage.reduce((sum, item) => sum + item.audio_seconds, 0)} 秒</span></div><div className="table-wrap"><table><thead><tr><th>时间</th><th>会话</th><th>音频秒数</th><th>字符数</th></tr></thead><tbody>{data.usage.map((item) => <tr key={item.id}><td>{readableTime(item.created_at)}</td><td><code>{shortId(item.session_id)}</code></td><td>{item.audio_seconds}</td><td>{item.characters}</td></tr>)}</tbody></table></div></section>
    </>}
  </section>
}

function CodesPage({ client }: { readonly client: CloudApiClient }): ReactElement {
  const [batches, setBatches] = useState<readonly CodeBatch[]>([])
  const [name, setName] = useState('内部测试')
  const [count, setCount] = useState(1)
  const [codes, setCodes] = useState<readonly string[] | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)

  const load = useCallback(async () => {
    const result = await client.listCodeBatches()
    if (result.kind === 'success') setBatches(result.data)
    else setError(failureMessage(result))
  }, [client])
  useEffect(() => {
    const timer = window.setTimeout(() => { void load() }, 0)
    return () => window.clearTimeout(timer)
  }, [load])

  const create = async (event: FormEvent<HTMLFormElement>): Promise<void> => {
    event.preventDefault()
    if (!window.confirm(`确认创建 ${count} 个一年期兑换码？明文只显示一次。`)) return
    setBusy(true); setError(null); setCodes(null)
    try {
      const result = await client.createCodeBatch(name, count)
      if (result.kind === 'success') { setCodes(result.data.codes); await load() } else setError(failureMessage(result))
    } catch {
      setError('创建失败，请检查网络后重试。')
    } finally {
      setBusy(false)
    }
  }
  const disableBatch = async (batchId: string): Promise<void> => {
    if (!window.confirm('确认停用该批次所有未兑换码？已兑换权益不受影响。')) return
    setBusy(true); setError(null)
    try {
      const result = await client.disableCodeBatch(batchId)
      if (result.kind === 'success') await load()
      else setError(failureMessage(result))
    } catch {
      setError('停用失败，请检查网络后重试。')
    } finally {
      setBusy(false)
    }
  }
  const download = (): void => {
    if (codes === null) return
    const blob = new Blob([`${codes.join('\n')}\n`], { type: 'text/plain;charset=utf-8' })
    const url = URL.createObjectURL(blob)
    const link = document.createElement('a'); link.href = url; link.download = 'dngmeng-redemption-codes.txt'; link.click(); URL.revokeObjectURL(url)
  }

  return <section><div className="page-heading"><div><p className="eyebrow">权益运营</p><h1>兑换码</h1><p>兑换码固定增加 365 天；数据库只保存哈希，明文只在创建响应中出现。</p></div></div>
    {error === null ? null : <div className="error-banner" role="alert">{error}</div>}
    <section className="panel code-create"><h2>创建批次</h2><form onSubmit={(event) => void create(event)}><label>批次名称<input maxLength={100} onChange={(event) => setName(event.target.value)} required value={name} /></label><label>数量<input max={1000} min={1} onChange={(event) => setCount(Number(event.target.value))} required type="number" value={count} /></label><button className="primary-button" disabled={busy} type="submit">{busy ? '正在创建…' : '创建一年兑换码'}</button></form></section>
    {codes === null ? null : <section className="one-time-codes" role="status"><strong>请立即保存：关闭或刷新后无法再次查看</strong><pre>{codes.join('\n')}</pre><div><button className="primary-button" onClick={download} type="button">下载 TXT</button><button className="secondary-button" onClick={() => setCodes(null)} type="button">我已安全保存</button></div></section>}
    <section className="panel resource-section"><div className="panel-heading"><h2>批次（最近 100 个）</h2><span>{batches.length} 个</span></div><div className="table-wrap"><table><thead><tr><th>名称</th><th>创建时间</th><th>总数</th><th>已兑换</th><th>未兑换</th><th>状态</th><th>操作</th></tr></thead><tbody>{batches.map((batch) => <tr key={batch.id}><td>{batch.name}</td><td>{readableTime(batch.created_at)}</td><td>{batch.total_codes}</td><td>{batch.redeemed_codes}</td><td>{batch.unredeemed_codes}</td><td>{batch.disabled_at === undefined ? '可兑换' : '已停用'}</td><td>{batch.disabled_at === undefined ? <button className="text-danger" disabled={busy} onClick={() => void disableBatch(batch.id)} type="button">停用</button> : '—'}</td></tr>)}</tbody></table></div></section>
  </section>
}

function AuditRow({ audit }: { readonly audit: AuditLog }): ReactElement {
  return <tr><td>{audit.action}</td><td>{audit.target_type}</td><td><code>{audit.target_id === undefined ? '—' : shortId(audit.target_id)}</code></td><td><code>{shortId(audit.admin_id)}</code></td><td>{readableTime(audit.created_at)}</td></tr>
}

export function App(): ReactElement {
  const [page, setPage] = useState<Page>('overview')
  const [session, setSession] = useState<AuthTokens | null>(() => loadAdminSession())
  const [authenticationState, setAuthenticationState] = useState<AuthenticationState>(session === null ? 'anonymous' : 'checking')
  const [currentUser, setCurrentUser] = useState<CurrentUser | null>(null)
  const [selectedUser, setSelectedUser] = useState<AdminUser | null>(null)
  const [loginError, setLoginError] = useState<string | null>(null)
  const [loginLoading, setLoginLoading] = useState(false)
  const [showSetup, setShowSetup] = useState(false)
  const [setupEnabled, setSetupEnabled] = useState(false)
  const [setupStatusLoading, setSetupStatusLoading] = useState(false)
  const [setupError, setSetupError] = useState<string | null>(null)
  const [setupLoading, setSetupLoading] = useState(false)
  const [platform, setPlatform] = useState<PlatformState | null>(null)
  const [platformError, setPlatformError] = useState<string | null>(null)
  const baseUrl = defaultBaseUrl()

  const endSession = useCallback((message: string | null): void => {
    setSession(null); clearAdminSession(); setCurrentUser(null); setPlatform(null); setAuthenticationState('anonymous'); setLoginError(message)
  }, [])
  const refreshAccess = useCallback(async (): Promise<string | null> => {
    if (session === null) return null
    const result = await new CloudApiClient(baseUrl).refresh(session.refreshToken)
    if (result.kind !== 'success') { endSession('登录状态已失效，请重新登录。'); return null }
    setSession(result.data); saveAdminSession(result.data); return result.data.accessToken
  }, [baseUrl, endSession, session])
  const client = useMemo(() => new CloudApiClient(baseUrl, session?.accessToken ?? '', refreshAccess, () => endSession('登录状态已失效，请重新登录。')), [baseUrl, endSession, refreshAccess, session?.accessToken])

  useEffect(() => {
    if (session === null) return
    void client.me().then((result) => {
      if (result.kind !== 'success') return endSession('登录状态已失效，请重新登录。')
      if (result.data.role !== 'admin') return endSession('当前账号没有管理权限。')
      setCurrentUser(result.data); setAuthenticationState('authenticated')
    })
  }, [client, endSession, session])

  const refreshPlatform = useCallback(async () => {
    setPlatformError(null)
    try { setPlatform(await client.checkPlatform()) } catch (reason) { setPlatformError(reason instanceof Error ? reason.message : '无法连接 Cloud API。') }
  }, [client])
  useEffect(() => {
    if (authenticationState !== 'authenticated') return
    const timer = window.setTimeout(() => { void refreshPlatform() }, 0)
    return () => window.clearTimeout(timer)
  }, [authenticationState, refreshPlatform])

  const login = async (identifier: string, password: string): Promise<void> => {
    setLoginLoading(true); setLoginError(null)
    const result = await new CloudApiClient(baseUrl).login(identifier.trim(), password)
    if (result.kind === 'success') { setSession(result.data); saveAdminSession(result.data); setAuthenticationState('checking') }
    else if (result.kind === 'unauthorized') setLoginError('账号或密码错误。')
    else if (result.status === null) setLoginError('连接 Cloud API 失败，请检查网络与服务地址。')
    else setLoginError(result.requestId === null ? '服务暂时无法处理登录请求。' : `服务暂时无法处理登录请求（请求 ID：${result.requestId}）。`)
    setLoginLoading(false)
  }
  const openSetup = async (): Promise<void> => {
    setShowSetup(true); setSetupStatusLoading(true); setSetupError(null)
    const result = await new CloudApiClient(baseUrl).adminSetupStatus()
    if (result.kind === 'success') setSetupEnabled(result.data.enabled)
    else setSetupError(result.status === null ? '连接 Cloud API 失败，请检查网络与服务地址。' : '无法确认设置权限，请稍后重试。')
    setSetupStatusLoading(false)
  }
  const completeSetup = async (token: string, username: string, email: string, password: string): Promise<void> => {
    setSetupLoading(true); setSetupError(null)
    const result = await new CloudApiClient(baseUrl).completeAdminSetup(token, username, email, password)
    if (result.kind === 'success') { setShowSetup(false); setLoginError('新管理员已设置完成，请使用新账号登录。') }
    else if (result.status === null) setSetupError('连接 Cloud API 失败，请检查网络与服务地址。')
    else setSetupError(result.requestId === null ? '设置未完成。请确认 token 有效后重试。' : `设置未完成（请求 ID：${result.requestId}）。`)
    setSetupLoading(false)
  }
  const logout = async (): Promise<void> => {
    try { if (session !== null) await client.logout(session.refreshToken) } finally { endSession(null) }
  }
  const loadUsers = useCallback((query: { readonly limit: number; readonly offset: number; readonly q?: string }) => client.listUsers(query), [client])
  const loadAuditLogs = useCallback((query: { readonly limit: number; readonly offset: number }) => client.listAuditLogs(query), [client])

  if (authenticationState === 'checking') return <main className="session-check" aria-busy="true"><span role="status">正在验证管理员身份…</span></main>
  if (authenticationState === 'anonymous') return showSetup
    ? <AdminSetup enabled={setupEnabled} error={setupError} loading={setupLoading} loadingStatus={setupStatusLoading} onBack={() => { setShowSetup(false); setSetupError(null) }} onSubmit={completeSetup} />
    : <AdminLogin error={loginError} loading={loginLoading} onSetup={() => void openSetup()} onSubmit={login} />

  const renderPage = (): ReactElement => {
    if (selectedUser !== null) return <UserDetail client={client} onBack={() => setSelectedUser(null)} user={selectedUser} />
    if (page === 'users') return <AdminListPanel<AdminUser> description="按用户名、用户 ID 或已知完整邮箱查找账户；列表仅显示脱敏邮箱。" emptyMessage="没有匹配用户。" endpoint="GET /api/v1/admin/users" eyebrow="账户运营" headers={['用户', '身份', '角色', '创建时间', '操作']} load={loadUsers} renderRow={(user) => <tr key={user.id}><td><code>{shortId(user.id)}</code></td><td>{user.username ?? user.email ?? '—'}</td><td><span className="role-badge">{user.disabled_at === undefined ? user.role : '已禁用'}</span></td><td>{readableTime(user.created_at)}</td><td><button className="table-action" onClick={() => setSelectedUser(user)} type="button">查看</button></td></tr>} searchLabel="搜索用户" title="用户" />
    if (page === 'codes') return <CodesPage client={client} />
    if (page === 'audit') return <AdminListPanel<AuditLog> description="只展示安全审计字段，不展开开放 metadata。" emptyMessage="暂无审计记录。" endpoint="GET /api/v1/admin/audit-logs" eyebrow="安全追踪" headers={['操作', '对象', '对象 ID', '管理员', '时间']} load={loadAuditLogs} renderRow={(audit) => <AuditRow audit={audit} key={audit.id} />} title="审计日志" />
    return <><section className="page-heading"><div><p className="eyebrow">运行概览</p><h1>服务运行态</h1><p>只展示服务器真实探针，不填充模拟业务指标。</p></div><button className="secondary-button" onClick={() => void refreshPlatform()} type="button">刷新状态</button></section>{platformError === null ? null : <div className="error-banner" role="alert">{platformError}</div>}<section className="metric-grid"><MetricCard detail="Cloud API" label="服务存活" value={platform?.health.status === 'ok' ? '正常' : '未确认'} /><MetricCard detail="PostgreSQL" label="数据就绪" value={platform?.ready.status === 'ok' ? '正常' : '未确认'} /><MetricCard detail="服务器配置" label="环境" value={platform?.config.environment ?? '未确认'} /><MetricCard detail="构建版本" label="版本" value={platform?.config.version ?? '未确认'} /></section>{platform === null ? null : <section className="panel"><ul className="status-list"><StatusRow result={platform.health} /><StatusRow result={platform.ready} /></ul></section>}</>
  }

  return <div className="app-shell"><a className="skip-link" href="#main-content">跳到主内容</a><header><div className="brand-lockup"><span className="brand-mark" aria-hidden="true">言</span><span><strong>言枢</strong><small>ADMIN CONSOLE</small></span></div><div className="header-actions"><div className="connection-indicator"><span className={platform?.health.status === 'ok' ? 'status-dot ok' : 'status-dot'} />{currentUser?.username ?? currentUser?.email ?? '管理员'}</div><button className="header-logout" onClick={() => void logout()} type="button">退出登录</button></div></header><div className="console-layout"><nav aria-label="管理导航"><p>控制台</p>{navigation.map((item) => <button aria-current={selectedUser === null && page === item.page ? 'page' : undefined} key={item.page} onClick={() => { setSelectedUser(null); setPage(item.page) }} type="button">{item.label}</button>)}</nav><main id="main-content">{renderPage()}</main></div></div>
}
