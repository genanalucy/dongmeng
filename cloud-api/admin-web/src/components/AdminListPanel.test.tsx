import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import { AdminListPanel } from './AdminListPanel'

type LoadResult =
  | {
    readonly kind: 'success'
    readonly data: readonly { readonly id: string }[]
    readonly requestId: null
  }
  | {
    readonly kind: 'error'
    readonly status: null
    readonly error: string
    readonly requestId: null
  }

interface Deferred<T> {
  readonly promise: Promise<T>
  readonly resolve: (value: T) => void
}

function deferred<T>(): Deferred<T> {
  let resolve: ((value: T) => void) | undefined
  const promise = new Promise<T>((settle) => { resolve = settle })
  if (resolve === undefined) throw new Error('deferred resolver was not initialized')
  return { promise, resolve }
}

function successfulPage(id: string, count = 1): LoadResult {
  return { kind: 'success', data: Array.from({ length: count }, (_, index) => ({ id: `${id}-${index}` })), requestId: null }
}

function failedPage(): LoadResult {
  return { kind: 'error', status: null, error: 'stale_error', requestId: null }
}

describe('AdminListPanel', () => {
  it('keeps the newer search page when the initial request completes afterwards and falls back to that page after an empty next page', async () => {
    const initial = deferred<LoadResult>()
    const search = deferred<LoadResult>()
    const emptyNextPage = deferred<LoadResult>()
    const load = vi.fn(({ offset, q }: { readonly limit: number; readonly offset: number; readonly q?: string }) => {
      if (q === undefined && offset === 0) return initial.promise
      if (q === 'new@example.com' && offset === 0) return search.promise
      if (q === 'new@example.com' && offset === 50) return emptyNextPage.promise
      throw new Error(`unexpected query: ${JSON.stringify({ offset, q })}`)
    })

    render(<AdminListPanel
      description="test"
      emptyMessage="empty"
      endpoint="GET /test"
      eyebrow="test"
      headers={['ID']}
      load={load}
      renderRow={(item) => <tr key={item.id}><td>{item.id}</td></tr>}
      searchLabel="搜索用户"
      title="列表"
    />)

    await waitFor(() => expect(load).toHaveBeenCalledWith({ limit: 50, offset: 0 }))
    fireEvent.change(screen.getByLabelText('搜索用户'), { target: { value: 'new@example.com' } })
    fireEvent.click(screen.getByRole('button', { name: '搜索' }))
    await waitFor(() => expect(load).toHaveBeenCalledWith({ limit: 50, offset: 0, q: 'new@example.com' }))

    search.resolve(successfulPage('new', 50))
    await screen.findByText('new-0')
    initial.resolve(successfulPage('stale', 50))
    await waitFor(() => expect(screen.queryByText('stale-0')).not.toBeInTheDocument())
    expect(screen.getByText('new-0')).toBeInTheDocument()
    expect(screen.getByText('第 1 页')).toBeInTheDocument()

    fireEvent.click(screen.getByRole('button', { name: '下一页' }))
    await waitFor(() => expect(load).toHaveBeenCalledWith({ limit: 50, offset: 50, q: 'new@example.com' }))
    emptyNextPage.resolve(successfulPage('empty', 0))

    await waitFor(() => expect(screen.getByText('new-0')).toBeInTheDocument())
    expect(screen.queryByText('stale-0')).not.toBeInTheDocument()
    expect(screen.getByText('第 1 页')).toBeInTheDocument()
  })

  it('ignores an older empty response after a newer search has loaded', async () => {
    const initial = deferred<LoadResult>()
    const search = deferred<LoadResult>()
    const load = vi.fn(({ q }: { readonly limit: number; readonly offset: number; readonly q?: string }) => q === undefined ? initial.promise : search.promise)

    render(<AdminListPanel
      description="test"
      emptyMessage="empty"
      endpoint="GET /test"
      eyebrow="test"
      headers={['ID']}
      load={load}
      renderRow={(item) => <tr key={item.id}><td>{item.id}</td></tr>}
      searchLabel="搜索用户"
      title="列表"
    />)

    await waitFor(() => expect(load).toHaveBeenCalledTimes(1))
    fireEvent.change(screen.getByLabelText('搜索用户'), { target: { value: 'new@example.com' } })
    fireEvent.click(screen.getByRole('button', { name: '搜索' }))
    await waitFor(() => expect(load).toHaveBeenCalledTimes(2))

    search.resolve(successfulPage('new'))
    await screen.findByText('new-0')
    initial.resolve(successfulPage('stale', 0))

    await waitFor(() => expect(screen.getByText('new-0')).toBeInTheDocument())
    expect(screen.queryByText('empty')).not.toBeInTheDocument()
    expect(screen.getByText('第 1 页')).toBeInTheDocument()
  })

  it('ignores an older error response after a newer search has loaded', async () => {
    const initial = deferred<LoadResult>()
    const search = deferred<LoadResult>()
    const load = vi.fn(({ q }: { readonly limit: number; readonly offset: number; readonly q?: string }) => q === undefined ? initial.promise : search.promise)

    render(<AdminListPanel
      description="test"
      emptyMessage="empty"
      endpoint="GET /test"
      eyebrow="test"
      headers={['ID']}
      load={load}
      renderRow={(item) => <tr key={item.id}><td>{item.id}</td></tr>}
      searchLabel="搜索用户"
      title="列表"
    />)

    await waitFor(() => expect(load).toHaveBeenCalledTimes(1))
    fireEvent.change(screen.getByLabelText('搜索用户'), { target: { value: 'new@example.com' } })
    fireEvent.click(screen.getByRole('button', { name: '搜索' }))
    await waitFor(() => expect(load).toHaveBeenCalledTimes(2))

    search.resolve(successfulPage('new'))
    await screen.findByText('new-0')
    initial.resolve(failedPage())

    await waitFor(() => expect(screen.getByText('new-0')).toBeInTheDocument())
    expect(screen.queryByText('读取失败')).not.toBeInTheDocument()
    expect(screen.getByText('第 1 页')).toBeInTheDocument()
  })

  it('returns to the previous page after an empty next page without issuing a looped request', async () => {
    const load = vi.fn(async ({ offset }: { readonly limit: number; readonly offset: number; readonly q?: string }) => ({
      kind: 'success' as const,
      data: offset === 0 ? Array.from({ length: 50 }, (_, index) => ({ id: String(index) })) : [],
      requestId: null,
    }))

    render(<AdminListPanel
      description="test"
      emptyMessage="empty"
      endpoint="GET /test"
      eyebrow="test"
      headers={['ID']}
      load={load}
      renderRow={(item) => <tr key={item.id}><td>{item.id}</td></tr>}
      title="列表"
    />)

    await screen.findByText('0')
    fireEvent.click(screen.getByRole('button', { name: '下一页' }))

    await waitFor(() => expect(load).toHaveBeenCalledWith({ limit: 50, offset: 50 }))
    await waitFor(() => expect(screen.getByText('0')).toBeInTheDocument())
    expect(screen.getByRole('button', { name: '上一页' })).toBeDisabled()
    expect(load).toHaveBeenCalledTimes(2)
  })
})
