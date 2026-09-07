import { useId, useState, type FormEvent, type ReactElement } from 'react'

interface AdminSetupProps {
  readonly enabled: boolean
  readonly loadingStatus: boolean
  readonly error: string | null
  readonly loading: boolean
  readonly onSubmit: (setupToken: string, username: string, email: string, password: string) => Promise<void>
  readonly onBack: () => void
}

export function AdminSetup({ enabled, loadingStatus, error, loading, onSubmit, onBack }: AdminSetupProps): ReactElement {
  const tokenId = useId(), usernameId = useId(), emailId = useId(), passwordId = useId(), confirmId = useId(), errorId = useId()
  const [token, setToken] = useState(''), [username, setUsername] = useState(''), [email, setEmail] = useState(''), [password, setPassword] = useState(''), [confirm, setConfirm] = useState('')
  const [confirmationError, setConfirmationError] = useState<string | null>(null)
  const submit = async (event: FormEvent<HTMLFormElement>): Promise<void> => {
    event.preventDefault()
    if (password !== confirm) { setConfirmationError('两次输入的密码不一致。'); return }
    setConfirmationError(null)
    await onSubmit(token, username, email, password)
    setToken(''); setUsername(''); setEmail(''); setPassword(''); setConfirm('')
  }
  const message = confirmationError ?? error
  return <main className="login-page" aria-labelledby="setup-heading">
    <section className="login-introduction"><div className="brand-lockup login-brand"><span className="brand-mark" aria-hidden="true">言</span><span><strong>言枢</strong><small>ADMIN CONSOLE</small></span></div><p className="login-kicker">离线恢复</p><h1 id="setup-heading">初次设置/重新设置管理员</h1><p>仅可使用本机离线生成、短时有效且一次性的 setup token。成功后将停用旧管理员并撤销其登录会话。</p></section>
    <section className="login-card" aria-busy={loading || loadingStatus}><div><p className="eyebrow">安全替换</p><h2>注册新管理员</h2></div>{message === null ? null : <div className="login-error" id={errorId} role="alert">{message}</div>}
      {loadingStatus ? <p role="status">正在检查设置权限…</p> : !enabled ? <>{error === null ? <p role="status">当前没有可用的设置令牌。请在服务器本机运行 <code>admin-setup-token</code> 后重试。</p> : null}<button className="secondary-button" onClick={onBack} type="button">返回登录</button></> : <form onSubmit={(event) => void submit(event)}>
        <label htmlFor={tokenId}>Setup token</label><input aria-describedby={message === null ? undefined : errorId} autoComplete="off" disabled={loading} id={tokenId} onChange={(event) => setToken(event.target.value)} required type="password" value={token} />
        <label htmlFor={usernameId}>管理员用户名</label><input autoComplete="username" disabled={loading} id={usernameId} maxLength={32} onChange={(event) => setUsername(event.target.value)} required value={username} />
        <label htmlFor={emailId}>管理员邮箱</label><input autoComplete="email" disabled={loading} id={emailId} onChange={(event) => setEmail(event.target.value)} required type="email" value={email} />
        <label htmlFor={passwordId}>密码</label><input autoComplete="new-password" disabled={loading} id={passwordId} onChange={(event) => setPassword(event.target.value)} required type="password" value={password} />
        <label htmlFor={confirmId}>确认密码</label><input aria-describedby={message === null ? undefined : errorId} autoComplete="new-password" disabled={loading} id={confirmId} onChange={(event) => setConfirm(event.target.value)} required type="password" value={confirm} />
        <button className="primary-button login-submit" disabled={loading} type="submit">{loading ? '正在安全替换…' : '安全替换现有管理员'}</button><button className="secondary-button" disabled={loading} onClick={onBack} type="button">返回登录</button>
      </form>}
    </section>
  </main>
}
