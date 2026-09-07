import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import { AdminSetup } from './AdminSetup'

describe('AdminSetup', () => {
  it('clears the offline token and password fields after successful submission', async () => {
    const onSubmit = vi.fn(() => Promise.resolve())
    render(<AdminSetup enabled error={null} loading={false} loadingStatus={false} onBack={vi.fn()} onSubmit={onSubmit} />)

    fireEvent.change(screen.getByLabelText('Setup token'), { target: { value: 'setup-token.example.test' } })
    fireEvent.change(screen.getByLabelText('管理员用户名'), { target: { value: 'replacement_admin' } })
    fireEvent.change(screen.getByLabelText('管理员邮箱'), { target: { value: 'replacement@example.test' } })
    fireEvent.change(screen.getByLabelText('密码'), { target: { value: 'fixture-password' } })
    fireEvent.change(screen.getByLabelText('确认密码'), { target: { value: 'fixture-password' } })
    fireEvent.click(screen.getByRole('button', { name: '安全替换现有管理员' }))

    await waitFor(() => expect(onSubmit).toHaveBeenCalledWith('setup-token.example.test', 'replacement_admin', 'replacement@example.test', 'fixture-password'))
    await waitFor(() => {
      expect(screen.getByLabelText('Setup token')).toHaveValue('')
      expect(screen.getByLabelText('密码')).toHaveValue('')
      expect(screen.getByLabelText('确认密码')).toHaveValue('')
    })
  })
})
