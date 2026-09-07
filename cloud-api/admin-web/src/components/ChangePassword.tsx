import { useId, useState, type FormEvent, type ReactElement } from 'react'

interface ChangePasswordProps {
  readonly error: string | null
  readonly loading: boolean
  readonly onSubmit: (currentPassword: string, newPassword: string) => Promise<boolean>
  readonly onCancel: () => void
}

// The three credentials live only in component state: they are never written
// to browser storage and never leave the page except in the authenticated
// POST body issued by App.onSubmit. Fields are cleared only after the server
// confirms the change, at which point App also ends the session.
export function ChangePassword({ error, loading, onSubmit, onCancel }: ChangePasswordProps): ReactElement {
  const currentId = useId(), newId = useId(), confirmId = useId(), errorId = useId()
  const [currentPassword, setCurrentPassword] = useState('')
  const [newPassword, setNewPassword] = useState('')
  const [confirmPassword, setConfirmPassword] = useState('')
  const [confirmationError, setConfirmationError] = useState<string | null>(null)
  const message = confirmationError ?? error

  const submit = async (event: FormEvent<HTMLFormElement>): Promise<void> => {
    event.preventDefault()
    if (currentPassword === '' || newPassword === '' || confirmPassword === '') {
      setConfirmationError('请填写全部密码字段。')
      return
    }
    if (newPassword !== confirmPassword) {
      setConfirmationError('两次输入的新密码不一致。')
      return
    }
    setConfirmationError(null)
    const succeeded = await onSubmit(currentPassword, newPassword)
    if (succeeded) {
      setCurrentPassword(''); setNewPassword(''); setConfirmPassword('')
    }
  }
  const cancel = (): void => {
    setCurrentPassword(''); setNewPassword(''); setConfirmPassword(''); setConfirmationError(null)
    onCancel()
  }

  return <section><div className="page-heading"><div><p className="eyebrow">账户安全</p><h1>修改管理员密码</h1><p>修改成功后当前登录会立即失效，需要使用新密码重新登录。</p></div></div>
    <section className="panel code-create" aria-busy={loading}>
      <h2>设置新密码</h2>
      {message === null ? null : <div className="error-banner" id={errorId} role="alert">{message}</div>}
      <form onSubmit={(event) => void submit(event)}>
        <label htmlFor={currentId}>当前密码<input aria-describedby={message === null ? undefined : errorId} autoComplete="current-password" disabled={loading} id={currentId} onChange={(event) => setCurrentPassword(event.target.value)} required type="password" value={currentPassword} /></label>
        <label htmlFor={newId}>新密码<input aria-describedby={message === null ? undefined : errorId} autoComplete="new-password" disabled={loading} id={newId} onChange={(event) => setNewPassword(event.target.value)} required type="password" value={newPassword} /></label>
        <label htmlFor={confirmId}>确认新密码<input aria-describedby={message === null ? undefined : errorId} autoComplete="new-password" disabled={loading} id={confirmId} onChange={(event) => setConfirmPassword(event.target.value)} required type="password" value={confirmPassword} /></label>
        <button className="primary-button" disabled={loading} type="submit">{loading ? '正在提交…' : '确认修改'}</button>
        <button className="secondary-button" disabled={loading} onClick={cancel} type="button">取消</button>
      </form>
    </section>
  </section>
}
