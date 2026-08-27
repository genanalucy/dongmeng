import { useCallback, useEffect, useState, type ReactElement } from 'react'
import { apiErrorMessage, type ApiResult } from '../api/cloudApi'

interface AdminListPanelProps<T> {
  readonly eyebrow: string
  readonly title: string
  readonly description: string
  readonly endpoint: string
  readonly emptyMessage: string
  readonly load: () => Promise<ApiResult<readonly T[]>>
  readonly headers: readonly string[]
  readonly renderRow: (item: T) => ReactElement
}

type ListState<T> =
  | { readonly kind: 'loading'; readonly data: readonly T[] | null }
  | { readonly kind: 'ready'; readonly data: readonly T[] }
  | { readonly kind: 'unavailable'; readonly error: string; readonly status: 404 | 501 }
  | { readonly kind: 'unauthorized'; readonly error: string }
  | { readonly kind: 'forbidden'; readonly error: string }
  | { readonly kind: 'error'; readonly error: string }

export function AdminListPanel<T>({ eyebrow, title, description, endpoint, emptyMessage, load, headers, renderRow }: AdminListPanelProps<T>): ReactElement {
  const [state, setState] = useState<ListState<T>>({ kind: 'loading', data: null })

  const refresh = useCallback(async (): Promise<void> => {
    setState((current) => ({ kind: 'loading', data: current.kind === 'ready' ? current.data : null }))
    const result = await load()
    if (result.kind === 'success') {
      setState({ kind: 'ready', data: result.data })
      return
    }
    if (result.kind === 'unavailable') {
      setState({ kind: 'unavailable', status: result.status, error: apiErrorMessage(result) })
      return
    }
    if (result.kind === 'unauthorized') {
      setState({ kind: 'unauthorized', error: apiErrorMessage(result) })
      return
    }
    if (result.kind === 'forbidden') {
      setState({ kind: 'forbidden', error: apiErrorMessage(result) })
      return
    }
    setState({ kind: 'error', error: apiErrorMessage(result) })
  }, [load])

  useEffect(() => {
    const timer = window.setTimeout(() => { void refresh() }, 0)
    return () => window.clearTimeout(timer)
  }, [refresh])

  const hasData = state.kind === 'ready' || (state.kind === 'loading' && state.data !== null)
  const data = state.kind === 'ready' ? state.data : state.kind === 'loading' ? state.data : null

  return (
    <section className="panel admin-list-panel" aria-busy={state.kind === 'loading'} aria-labelledby={`${title}-heading`}>
      <div className="panel-heading">
        <div><p className="eyebrow">{eyebrow}</p><h1 id={`${title}-heading`}>{title}</h1><p>{description}</p></div>
        <button className="secondary-button" disabled={state.kind === 'loading'} onClick={() => void refresh()} type="button">{state.kind === 'loading' ? '正在读取…' : '刷新'}</button>
      </div>
      <code className="endpoint-label">{endpoint}</code>
      {state.kind === 'loading' && !hasData ? <p className="empty-state" role="status">正在读取已授权的实时数据…</p> : null}
      {state.kind === 'unavailable' ? <ControlledState title="接口待接入" message={`服务返回 ${state.status}；不会展示缓存或模拟记录。${state.error}`} /> : null}
      {state.kind === 'unauthorized' ? <ControlledState title="登录状态已失效" message={`服务返回 401。请重新登录后再试。${state.error}`} /> : null}
      {state.kind === 'forbidden' ? <ControlledState title="权限不足" message={`服务返回 403。当前账号不具备此资源的管理员权限。${state.error}`} /> : null}
      {state.kind === 'error' ? <ControlledState title="读取失败" message={state.error} retry={refresh} /> : null}
      {data !== null && data.length === 0 ? <p className="empty-state">{emptyMessage}</p> : null}
      {data !== null && data.length > 0 ? <div className="table-wrap"><table><thead><tr>{headers.map((header) => <th key={header} scope="col">{header}</th>)}</tr></thead><tbody>{data.map(renderRow)}</tbody></table></div> : null}
    </section>
  )
}

function ControlledState({ title, message, retry }: { readonly title: string; readonly message: string; readonly retry?: () => Promise<void> }): ReactElement {
  return <div className="resource-state" role="status"><strong>{title}</strong><span>{message}</span>{retry === undefined ? null : <button className="secondary-button" onClick={() => void retry()} type="button">重试</button>}</div>
}
