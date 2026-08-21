import { useState } from 'react'
import {
  deriveWebSocketUrl,
  restoreDefaultEndpointConfiguration,
  saveEndpointConfiguration,
  type EndpointConfiguration,
  validateEndpointConfiguration,
} from '../translation/EndpointConfiguration'
import { AgentHealthService, type AgentHealthStatus, type FetchResponsePort } from '../translation/AgentHealthService'

interface TestConnectionPageProps {
  readonly initialConfiguration: EndpointConfiguration
  readonly onSaved: (configuration: EndpointConfiguration) => void
  readonly fetcher?: (input: string) => Promise<FetchResponsePort>
}

type CheckResult = { readonly status: AgentHealthStatus; readonly message: string } | null

export function TestConnectionPage({ initialConfiguration, onSaved, fetcher }: TestConnectionPageProps): JSX.Element {
  const [configuration, setConfiguration] = useState(initialConfiguration)
  const [message, setMessage] = useState<string | null>(null)
  const [checkResult, setCheckResult] = useState<CheckResult>(null)
  const [checking, setChecking] = useState(false)

  const update = (key: keyof EndpointConfiguration, value: string): void => {
    setConfiguration((current) => ({ ...current, [key]: value }))
    setMessage(null)
    setCheckResult(null)
  }

  const deriveWs = (): void => {
    try {
      update('agentWsUrl', deriveWebSocketUrl(configuration.agentHttpUrl))
    } catch (error) {
      setMessage(errorMessage(error))
    }
  }

  const save = (): void => {
    try {
      const saved = saveEndpointConfiguration(configuration)
      setConfiguration(saved)
      onSaved(saved)
      setMessage('已保存。仅后续新会话使用新地址。')
    } catch (error) {
      setMessage(errorMessage(error))
    }
  }

  const restore = (): void => {
    const restored = restoreDefaultEndpointConfiguration()
    setConfiguration(restored)
    onSaved(restored)
    setMessage('已恢复本地默认值。')
    setCheckResult(null)
  }

  const check = async (): Promise<void> => {
    try {
      const valid = validateEndpointConfiguration(configuration)
      setChecking(true)
      setCheckResult(null)
      const service = new AgentHealthService({
        fetcher,
        getHealthUrl: () => new URL('/api/health', valid.agentHttpUrl).toString(),
      })
      const status = await service.check()
      setCheckResult({ status, message: service.getSnapshot().errorMessage ?? '连接成功，Agent 健康。' })
    } catch (error) {
      setCheckResult({ status: 'offline', message: errorMessage(error) })
    } finally {
      setChecking(false)
    }
  }

  return (
    <main className="settings-page">
      <section className="settings-panel" aria-labelledby="test-connection-heading">
        <div className="settings-heading">
          <p className="eyebrow">DEVELOPMENT ONLY</p>
          <h1 id="test-connection-heading">测试连接</h1>
          <p>仅开发测试，生产使用受认证 Gateway。</p>
        </div>
        <div className="endpoint-fields">
          <label>Web URL<input aria-label="Web URL" value={configuration.webUrl} onChange={(event) => update('webUrl', event.target.value)} /></label>
          <label>Agent HTTP URL<input aria-label="Agent HTTP URL" value={configuration.agentHttpUrl} onChange={(event) => update('agentHttpUrl', event.target.value)} /></label>
          <label>Agent WS URL<input aria-label="Agent WS URL" value={configuration.agentWsUrl} onChange={(event) => update('agentWsUrl', event.target.value)} /></label>
        </div>
        <div className="settings-actions">
          <button className="secondary-button" type="button" onClick={deriveWs}>从 HTTP 推导 WS</button>
          <button className="primary-action" type="button" onClick={save}>保存地址</button>
          <button className="text-action" type="button" onClick={restore}>恢复本地默认值</button>
        </div>
        {message !== null && <p className="settings-message" role="status">{message}</p>}
        <div className="connection-check">
          <div><strong>Agent health</strong><p>检查 <code>/api/health</code>。</p></div>
          <button className="health-check-button" type="button" disabled={checking} onClick={() => { void check() }}>{checking ? '检查中…' : '检查连接'}</button>
        </div>
        {checkResult !== null && <p className={`connection-result ${checkResult.status}`} role="status">{checkResult.status === 'online' ? '成功：' : '失败：'}{checkResult.message}</p>}
      </section>
    </main>
  )
}

function errorMessage(error: unknown): string {
  return error instanceof Error ? error.message : '地址配置失败'
}
