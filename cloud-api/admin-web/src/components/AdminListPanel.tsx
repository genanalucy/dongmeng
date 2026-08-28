import { useCallback, useEffect, useRef, useState, type FormEvent, type ReactElement } from 'react'
import { apiErrorMessage, type ApiResult, type Pagination } from '../api/cloudApi'

const pageSize = 50

type ListQuery = Pagination & { readonly q?: string }

interface AdminListPanelProps<T> {
  readonly eyebrow: string
  readonly title: string
  readonly description: string
  readonly endpoint: string
  readonly emptyMessage: string
  readonly load: (query: ListQuery) => Promise<ApiResult<readonly T[]>>
  readonly headers: readonly string[]
  readonly renderRow: (item: T) => ReactElement
  readonly searchLabel?: string
}

type ListState<T> =
  | { readonly kind: 'loading'; readonly data: readonly T[] | null }
  | { readonly kind: 'ready'; readonly data: readonly T[] }
  | { readonly kind: 'unavailable'; readonly error: string; readonly status: 404 | 501 }
  | { readonly kind: 'unauthorized'; readonly error: string }
  | { readonly kind: 'forbidden'; readonly error: string }
  | { readonly kind: 'error'; readonly error: string }

interface LastNonEmptyPage<T> {
  readonly search: string
  readonly offset: number
  readonly data: readonly T[]
}

export function AdminListPanel<T>({ eyebrow, title, description, endpoint, emptyMessage, load, headers, renderRow, searchLabel }: AdminListPanelProps<T>): ReactElement {
  const [state, setState] = useState<ListState<T>>({ kind: 'loading', data: null })
  const requestGeneration = useRef(0)
  const lastNonEmptyPage = useRef<LastNonEmptyPage<T> | null>(null)
  const [offset, setOffset] = useState(0)
  const [searchInput, setSearchInput] = useState('')
  const [search, setSearch] = useState('')

  const loadPage = useCallback(async (nextOffset: number, nextSearch: string): Promise<void> => {
    const generation = requestGeneration.current + 1
    requestGeneration.current = generation
    const cachedPage = lastNonEmptyPage.current
    const loadingData = cachedPage !== null && cachedPage.search === nextSearch ? cachedPage.data : null
    setState({ kind: 'loading', data: loadingData })
    const result = await load({ limit: pageSize, offset: nextOffset, ...(nextSearch === '' ? {} : { q: nextSearch }) })
    if (generation !== requestGeneration.current) return
    if (result.kind === 'success') {
      const previousPage = lastNonEmptyPage.current
      if (result.data.length === 0 && nextOffset > 0 && previousPage !== null
        && previousPage.search === nextSearch && previousPage.offset === nextOffset - pageSize) {
        setOffset(previousPage.offset)
        setState({ kind: 'ready', data: previousPage.data })
        return
      }
      if (result.data.length > 0) lastNonEmptyPage.current = { search: nextSearch, offset: nextOffset, data: result.data }
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
    const timer = window.setTimeout(() => { void loadPage(0, '') }, 0)
    return () => window.clearTimeout(timer)
  }, [loadPage])

  const submitSearch = (event: FormEvent<HTMLFormElement>): void => {
    event.preventDefault()
    const nextSearch = searchInput.trim()
    setSearch(nextSearch)
    setOffset(0)
    void loadPage(0, nextSearch)
  }
  const hasData = state.kind === 'ready' || (state.kind === 'loading' && state.data !== null)
  const data = state.kind === 'ready' ? state.data : state.kind === 'loading' ? state.data : null
  const nextDisabled = state.kind === 'loading' || (state.kind === 'ready' && state.data.length < pageSize)

  return (
    <section className="panel admin-list-panel" aria-busy={state.kind === 'loading'} aria-labelledby={`${title}-heading`}>
      <div className="panel-heading">
        <div><p className="eyebrow">{eyebrow}</p><h1 id={`${title}-heading`}>{title}</h1><p>{description}</p></div>
        <button className="secondary-button" disabled={state.kind === 'loading'} onClick={() => void loadPage(offset, search)} type="button">{state.kind === 'loading' ? '正在读取…' : '刷新'}</button>
      </div>
      <code className="endpoint-label">{endpoint}</code>
      {searchLabel === undefined ? null : <form className="admin-list-search" onSubmit={submitSearch}><label htmlFor={`${title}-search`}>{searchLabel}</label><div><input id={`${title}-search`} onChange={(event) => setSearchInput(event.target.value)} value={searchInput} /><button className="secondary-button" type="submit">搜索</button></div></form>}
      {state.kind === 'loading' && !hasData ? <p className="empty-state" role="status">正在读取已授权的实时数据…</p> : null}
      {state.kind === 'unavailable' ? <ControlledState title="接口待接入" message={`服务返回 ${state.status}；不会展示缓存或模拟记录。${state.error}`} /> : null}
      {state.kind === 'unauthorized' ? <ControlledState title="登录状态已失效" message={`服务返回 401。请重新登录后再试。${state.error}`} /> : null}
      {state.kind === 'forbidden' ? <ControlledState title="权限不足" message={`服务返回 403。当前账号不具备此资源的管理员权限。${state.error}`} /> : null}
      {state.kind === 'error' ? <ControlledState title="读取失败" message={state.error} retry={() => loadPage(offset, search)} /> : null}
      {data !== null && data.length === 0 ? <p className="empty-state">{emptyMessage}</p> : null}
      {data !== null && data.length > 0 ? <div className="table-wrap"><table><thead><tr>{headers.map((header) => <th key={header} scope="col">{header}</th>)}</tr></thead><tbody>{data.map(renderRow)}</tbody></table></div> : null}
      {data === null ? null : <div className="admin-list-pagination"><button className="secondary-button" disabled={state.kind === 'loading' || offset === 0} onClick={() => { const nextOffset = offset - pageSize; setOffset(nextOffset); void loadPage(nextOffset, search) }} type="button">上一页</button><span>第 {offset / pageSize + 1} 页</span><button className="secondary-button" disabled={nextDisabled} onClick={() => { const nextOffset = offset + pageSize; setOffset(nextOffset); void loadPage(nextOffset, search) }} type="button">下一页</button></div>}
    </section>
  )
}

function ControlledState({ title, message, retry }: { readonly title: string; readonly message: string; readonly retry?: () => Promise<void> }): ReactElement {
  return <div className="resource-state" role="status"><strong>{title}</strong><span>{message}</span>{retry === undefined ? null : <button className="secondary-button" onClick={() => void retry()} type="button">重试</button>}</div>
}
