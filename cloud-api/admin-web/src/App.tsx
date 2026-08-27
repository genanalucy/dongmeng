import { useCallback, useEffect, useMemo, useState, type ReactElement } from 'react'
import { clearAdminSession, loadAdminSession, saveAdminSession } from './api/adminSession'
import { CloudApiClient, type AdminUser, type AuditLog, type AuthTokens, type CurrentUser, type ProbeResult, type ServiceConfig } from './api/cloudApi'
import { AdminListPanel } from './components/AdminListPanel'
import { AdminLogin } from './components/AdminLogin'
import { ConnectionSettings } from './components/ConnectionSettings'
import { UnavailablePanel } from './components/UnavailablePanel'

const sessionBaseUrlKey = 'cloud-api.admin.base-url'

function defaultBaseUrl(): string {
  if (typeof window === 'undefined') return 'http://127.0.0.1:8080'
  const host = window.location.hostname
  return `http://${host === '' || host === 'localhost' ? '127.0.0.1' : host}:8080`
}

function developmentAliasEmail(): string | null {
  const enabled = import.meta.env.VITE_ENABLE_ADMIN_DEV_ALIAS === undefined
    ? import.meta.env.DEV
    : import.meta.env.VITE_ENABLE_ADMIN_DEV_ALIAS === 'true'
  return enabled && import.meta.env.DEV ? import.meta.env.VITE_ADMIN_DEV_ALIAS_EMAIL ?? 'admin@123.com' : null
}

type Page = 'overview' | 'users' | 'entitlements' | 'codes' | 'translation' | 'feedback' | 'audit' | 'settings'
type PlatformState = { readonly health: ProbeResult; readonly ready: ProbeResult; readonly config: ServiceConfig }
type AuthenticationState = 'checking' | 'anonymous' | 'authenticated'

const navigation: ReadonlyArray<{ readonly page: Page; readonly label: string }> = [
  { page: 'overview', label: '概览' }, { page: 'users', label: '用户' }, { page: 'entitlements', label: '权益' }, { page: 'codes', label: '兑换码' }, { page: 'translation', label: '翻译会话' }, { page: 'feedback', label: '反馈' }, { page: 'audit', label: '审计' }, { page: 'settings', label: '连接设置' },
]

function sessionValue(key: string, fallback = ''): string {
  try { return sessionStorage.getItem(key) ?? fallback } catch { return fallback }
}

function saveSessionValue(key: string, value: string): void {
  try { sessionStorage.setItem(key, value) } catch { /* 配置仅保留内存。 */ }
}

function MetricCard({ label, value, detail }: { readonly label: string; readonly value: string; readonly detail: string }): ReactElement {
  return <article className="metric-card"><p>{label}</p><strong>{value}</strong><span>{detail}</span></article>
}

function StatusRow({ result }: { readonly result: ProbeResult }): ReactElement {
  return <li><span className={`status-dot ${result.status}`} aria-hidden="true" /><span><strong>{result.label}</strong><small>{result.detail}</small></span><span className={`status-label ${result.status}`}>{result.status === 'ok' ? '正常' : '异常'}</span></li>
}

function readableTime(value: string): string {
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? value : new Intl.DateTimeFormat('zh-CN', { dateStyle: 'medium', timeStyle: 'medium' }).format(date)
}

function UserRow({ user }: { readonly user: AdminUser }): ReactElement {
  return <tr key={user.id}><td><code>{user.id}</code></td><td>{user.email}</td><td><span className="role-badge">{user.role}</span></td><td>{readableTime(user.created_at)}</td></tr>
}

function AuditRow({ audit }: { readonly audit: AuditLog }): ReactElement {
  return <tr key={audit.id}><td>{audit.action}</td><td>{audit.target_type}</td><td><code>{audit.target_id ?? '—'}</code></td><td><code>{audit.admin_id}</code></td><td>{readableTime(audit.created_at)}</td></tr>
}

export function App(): ReactElement {
  const [page, setPage] = useState<Page>('overview')
  const [baseUrl, setBaseUrl] = useState(() => sessionValue(sessionBaseUrlKey, defaultBaseUrl()))
  const [session, setSession] = useState<AuthTokens | null>(() => loadAdminSession())
  const [authenticationState, setAuthenticationState] = useState<AuthenticationState>(session === null ? 'anonymous' : 'checking')
  const [currentUser, setCurrentUser] = useState<CurrentUser | null>(null)
  const [loginError, setLoginError] = useState<string | null>(null)
  const [loginLoading, setLoginLoading] = useState(false)
  const [platform, setPlatform] = useState<PlatformState | null>(null)
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState<string | null>(null)

  const endSession = useCallback((message: string | null): void => {
    setSession(null)
    clearAdminSession()
    setCurrentUser(null)
    setPlatform(null)
    setAuthenticationState('anonymous')
    setLoginError(message)
  }, [])

  const refreshAccess = useCallback(async (): Promise<string | null> => {
    if (session === null) return null
    const result = await new CloudApiClient(baseUrl).refresh(session.refreshToken)
    if (result.kind !== 'success') {
      endSession('登录状态已失效，请重新登录。')
      return null
    }
    setSession(result.data)
    saveAdminSession(result.data)
    return result.data.accessToken
  }, [baseUrl, endSession, session])

  const authenticatedClient = useMemo(() => new CloudApiClient(
    baseUrl,
    session?.accessToken ?? '',
    refreshAccess,
    () => endSession('登录状态已失效，请重新登录。'),
  ), [baseUrl, endSession, refreshAccess, session?.accessToken])

  const verifyAdministrator = useCallback(async (): Promise<boolean> => {
    const result = await authenticatedClient.me()
    if (result.kind !== 'success') {
      endSession('登录状态已失效，请重新登录。')
      return false
    }
    if (result.data.role !== 'admin') {
      endSession('当前账号没有管理权限。')
      return false
    }
    setCurrentUser(result.data)
    setAuthenticationState('authenticated')
    return true
  }, [authenticatedClient, endSession])

  useEffect(() => {
    if (session === null) return
    const timer = window.setTimeout(() => { void verifyAdministrator() }, 0)
    return () => window.clearTimeout(timer)
  }, [session, verifyAdministrator])

  const refreshPlatform = useCallback(async (): Promise<void> => {
    setLoading(true)
    setError(null)
    try { setPlatform(await authenticatedClient.checkPlatform()) } catch (reason) { setPlatform(null); setError(reason instanceof Error ? reason.message : '无法连接 Cloud API。') } finally { setLoading(false) }
  }, [authenticatedClient])

  useEffect(() => {
    if (authenticationState !== 'authenticated') return
    const timer = window.setTimeout(() => { void refreshPlatform() }, 0)
    return () => window.clearTimeout(timer)
  }, [authenticationState, refreshPlatform])

  const login = async (account: string, password: string): Promise<void> => {
    setLoginLoading(true)
    setLoginError(null)
    const aliasEmail = developmentAliasEmail()
    const email = account.trim() === 'admin' && aliasEmail !== null ? aliasEmail : account.trim()
    try {
      const result = await new CloudApiClient(baseUrl).login(email, password)
      if (result.kind !== 'success') {
        setLoginError('账号或密码错误，或暂时无法登录。')
        return
      }
      setSession(result.data)
      saveAdminSession(result.data)
      setAuthenticationState('checking')
    } finally {
      setLoginLoading(false)
    }
  }

  const logout = async (): Promise<void> => {
    try {
      if (session !== null) await new CloudApiClient(baseUrl).logout(session.refreshToken)
    } finally {
      endSession(null)
    }
  }

  const saveConnection = (nextBaseUrl: string): void => {
    setBaseUrl(nextBaseUrl)
    saveSessionValue(sessionBaseUrlKey, nextBaseUrl)
  }

  const loadUsers = useCallback(() => authenticatedClient.listUsers(), [authenticatedClient])
  const loadAuditLogs = useCallback(() => authenticatedClient.listAuditLogs(), [authenticatedClient])

  const renderPage = (): ReactElement => {
    if (page === 'settings') return <ConnectionSettings baseUrl={baseUrl} onSave={saveConnection} />
    if (page === 'overview') {
      return <>
        <section className="page-heading"><div><p className="eyebrow">运行概览</p><h1>服务运行态</h1><p>仅展示 Cloud API 已返回的数据；管理业务指标不会以模拟值填充。</p></div><button className="secondary-button" disabled={loading} onClick={() => void refreshPlatform()} type="button">{loading ? '正在刷新…' : '刷新状态'}</button></section>
        {error === null ? null : <div className="error-banner" role="alert"><strong>状态读取失败</strong><span>{error}</span><button onClick={() => void refreshPlatform()} type="button">重试</button></div>}
        <section className="metric-grid" aria-label="服务指标"><MetricCard detail="由 /api/v1/health 返回" label="服务存活" value={platform?.health.status === 'ok' ? '正常' : '未确认'} /><MetricCard detail="由 /api/v1/ready 返回" label="数据就绪" value={platform?.ready.status === 'ok' ? '正常' : '未确认'} /><MetricCard detail="由 /api/v1/config 返回" label="部署环境" value={platform?.config.environment ?? '未确认'} /><MetricCard detail="管理统计接口尚未提供" label="业务指标" value="待接入" /></section>
        <section className="panel health-panel" aria-busy={loading} aria-labelledby="health-heading"><div className="panel-heading"><div><p className="eyebrow">平台探针</p><h2 id="health-heading">健康与版本</h2></div>{loading ? <span className="loading-label" role="status">正在请求…</span> : null}</div>{platform === null ? <p className="empty-state">尚未获得可验证的平台响应。请检查 API Base URL、网络及 CORS 配置。</p> : <><ul className="status-list"><StatusRow result={platform.health} /><StatusRow result={platform.ready} /></ul><dl className="metadata"><div><dt>服务</dt><dd>{platform.config.service}</dd></div><div><dt>版本</dt><dd>{platform.config.version}</dd></div><div><dt>环境</dt><dd>{platform.config.environment}</dd></div><div><dt>请求地址</dt><dd>{baseUrl}</dd></div></dl></>}</section>
      </>
    }
    if (page === 'users') return <AdminListPanel<AdminUser> description="仅在服务已实现管理员用户列表且返回契约匹配时显示实时用户；不会回退为模拟记录。" emptyMessage="接口已返回，但当前没有可显示的用户。" endpoint="GET /api/v1/admin/users" eyebrow="管理员资源" headers={['用户 ID', '邮箱', '角色', '创建时间']} load={loadUsers} renderRow={(user) => <UserRow key={user.id} user={user} />} title="用户" />
    if (page === 'audit') return <AdminListPanel<AuditLog> description="仅在服务已实现审计读取端点且返回契约匹配时显示实时日志；元数据不会在此页面展开。" emptyMessage="接口已返回，但当前没有可显示的审计日志。" endpoint="GET /api/v1/admin/audit-logs" eyebrow="管理员资源" headers={['操作', '对象类型', '对象 ID', '管理员 ID', '时间']} load={loadAuditLogs} renderRow={(audit) => <AuditRow audit={audit} key={audit.id} />} title="审计日志" />

    const unavailable: Record<Exclude<Page, 'overview' | 'settings' | 'users' | 'audit'>, { readonly title: string; readonly description: string; readonly endpoint?: string }> = {
      entitlements: { title: '授予与撤销权益', description: '当前 API 没有管理员权益查询、授予或撤销端点；操作控件保持不可用。' },
      codes: { title: '兑换码批次', description: '仅预留创建批次路由且当前返回 501；批次列表、详情与失效控制端点尚未定义，因而不显示表格或创建表单。', endpoint: 'POST /api/v1/admin/code-batches' },
      translation: { title: '翻译会话与用量', description: '当前 API 仅预留客户端创建会话和记录用量路由，缺少管理员读取、筛选与聚合端点。' },
      feedback: { title: '反馈数据', description: '当前 API 仅预留反馈工件写入/按工件 ID 读取路由，尚无管理员反馈列表、筛选与脱敏统计字段。' },
    }
    return <UnavailablePanel {...unavailable[page]} />
  }

  if (authenticationState === 'checking') return <main className="session-check" aria-busy="true"><span role="status">正在验证管理员身份…</span></main>
  if (authenticationState === 'anonymous') return <AdminLogin error={loginError} loading={loginLoading} onSubmit={login} />

  return <div className="app-shell"><a className="skip-link" href="#main-content">跳到主内容</a><header><div className="brand-lockup"><span className="brand-mark" aria-hidden="true">言</span><span><strong>言枢</strong><small>ADMIN CONSOLE</small></span></div><div className="header-actions"><div className="connection-indicator" role="status"><span className={platform?.health.status === 'ok' ? 'status-dot ok' : 'status-dot'} aria-hidden="true" />{currentUser?.email ?? '管理员'}</div><button className="header-logout" onClick={() => void logout()} type="button">退出登录</button></div></header><div className="console-layout"><nav aria-label="管理导航"><p>控制台</p>{navigation.map((item) => <button aria-current={page === item.page ? 'page' : undefined} key={item.page} onClick={() => setPage(item.page)} type="button">{item.label}</button>)}</nav><main id="main-content">{renderPage()}</main></div></div>
}
