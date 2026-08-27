import { useState, type ReactElement } from 'react'

interface ConnectionSettingsProps {
  readonly baseUrl: string
  readonly onSave: (baseUrl: string) => void
}

export function ConnectionSettings({ baseUrl, onSave }: ConnectionSettingsProps): ReactElement {
  const [nextBaseUrl, setNextBaseUrl] = useState(baseUrl)
  const [message, setMessage] = useState<string | null>(null)

  const save = (): void => {
    const normalized = nextBaseUrl.trim().replace(/\/$/, '')
    if (!/^https?:\/\/[^\s]+$/u.test(normalized)) {
      setMessage('请输入以 http:// 或 https:// 开头的 API Base URL。')
      return
    }
    onSave(normalized)
    setMessage('已保存 API Base URL。')
  }

  return (
    <section className="connection-settings" aria-labelledby="connection-heading">
      <div>
        <p className="eyebrow">连接配置</p>
        <h2 id="connection-heading">API 地址诊断</h2>
        <p>仅在连接诊断或部署切换时修改 API Base URL。管理员身份由登录流程管理。</p>
      </div>
      <label htmlFor="api-base-url">API Base URL</label>
      <input
        aria-describedby="base-url-help"
        autoComplete="url"
        id="api-base-url"
        onChange={(event) => setNextBaseUrl(event.target.value)}
        type="url"
        value={nextBaseUrl}
      />
      <p className="field-help" id="base-url-help">例如 http://127.0.0.1:8080。服务必须允许本页面所在 Origin 的 CORS。</p>
      <div className="settings-actions"><button className="primary-button" onClick={save} type="button">保存 API 地址</button></div>
      {message === null ? null : <p className="form-message" role="status">{message}</p>}
    </section>
  )
}
