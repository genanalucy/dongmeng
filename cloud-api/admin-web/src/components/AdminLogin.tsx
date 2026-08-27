import { useId, useState, type FormEvent, type ReactElement } from 'react'

interface AdminLoginProps {
  readonly onSubmit: (account: string, password: string) => Promise<void>
  readonly error: string | null
  readonly loading: boolean
}

export function AdminLogin({ onSubmit, error, loading }: AdminLoginProps): ReactElement {
  const accountId = useId()
  const passwordId = useId()
  const errorId = useId()
  const [account, setAccount] = useState('')
  const [password, setPassword] = useState('')

  const submit = async (event: FormEvent<HTMLFormElement>): Promise<void> => {
    event.preventDefault()
    await onSubmit(account, password)
    setPassword('')
  }

  return (
    <main className="login-page" aria-labelledby="login-heading">
      <section className="login-introduction">
        <div className="brand-lockup login-brand"><span className="brand-mark" aria-hidden="true">言</span><span><strong>言枢</strong><small>ADMIN CONSOLE</small></span></div>
        <p className="login-kicker">受限管理入口</p>
        <h1 id="login-heading">管理员登录</h1>
        <p>使用已分配的管理员账号进入控制台。登录信息仅用于本次请求。</p>
      </section>
      <section className="login-card" aria-labelledby="login-form-heading" aria-busy={loading}>
        <div><p className="eyebrow">身份验证</p><h2 id="login-form-heading">进入管理控制台</h2></div>
        {error === null ? null : <div className="login-error" id={errorId} role="alert">{error}</div>}
        <form onSubmit={(event) => void submit(event)}>
          <label htmlFor={accountId}>管理员邮箱</label>
          <input
            aria-describedby={error === null ? undefined : errorId}
            autoComplete="username"
            disabled={loading}
            id={accountId}
            onChange={(event) => setAccount(event.target.value)}
            required
            type="text"
            value={account}
          />
          <label htmlFor={passwordId}>密码</label>
          <input
            aria-describedby={error === null ? undefined : errorId}
            autoComplete="current-password"
            disabled={loading}
            id={passwordId}
            onChange={(event) => setPassword(event.target.value)}
            required
            type="password"
            value={password}
          />
          <button className="primary-button login-submit" disabled={loading} type="submit">{loading ? '正在登录…' : '登录'}</button>
        </form>
      </section>
    </main>
  )
}
