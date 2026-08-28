import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import { AdminListPanel } from './AdminListPanel'

describe('AdminListPanel', () => {
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
