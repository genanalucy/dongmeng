import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { App } from './App'

afterEach(() => {
  vi.unstubAllGlobals()
  sessionStorage.clear()
})

function jsonResponse(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })
}

function adminUser(): Response {
  return jsonResponse({ id: 'admin-1', email: 'admin@example.com', role: 'admin' })
}

function platformResponse(url: string): Response {
  if (url.endsWith('/health')) return jsonResponse({ status: 'ok', service: 'cloud-api' })
  if (url.endsWith('/ready')) return jsonResponse({ status: 'ready', service: 'cloud-api' })
  return jsonResponse({ service: 'cloud-api', environment: 'test', version: 'v1.2.3' })
}

describe('App', () => {
  it('shows the administrator login page by default without requesting protected resources', () => {
    const fetchMock = vi.fn()
    vi.stubGlobal('fetch', fetchMock)

    render(<App />)

    expect(screen.getByRole('heading', { name: '管理员登录' })).toBeInTheDocument()
    expect(screen.getByLabelText('管理员邮箱')).toBeInTheDocument()
    expect(screen.getByLabelText('密码')).toHaveAttribute('type', 'password')
    expect(fetchMock).not.toHaveBeenCalled()
  })

  it('logs in, verifies the administrator role, and stores credentials only in session storage', async () => {
    const fetchMock = vi.fn((url: string) => {
      if (url.endsWith('/auth/login')) return Promise.resolve(jsonResponse({ access_token: 'access-value', refresh_token: 'refresh-value', token_type: 'Bearer', expires_in: 900 }))
      if (url.endsWith('/users/me')) return Promise.resolve(adminUser())
      return Promise.resolve(platformResponse(url))
    })
    vi.stubGlobal('fetch', fetchMock)

    render(<App />)
    fireEvent.change(screen.getByLabelText('管理员邮箱'), { target: { value: 'admin@example.com' } })
    fireEvent.change(screen.getByLabelText('密码'), { target: { value: 'test-credential' } })
    fireEvent.click(screen.getByRole('button', { name: '登录' }))

    expect(await screen.findByRole('heading', { name: '服务运行态' })).toBeInTheDocument()
    expect(sessionStorage.getItem('cloud-api.admin.access-token')).toBe('access-value')
    expect(sessionStorage.getItem('cloud-api.admin.refresh-token')).toBe('refresh-value')
    expect(localStorage.getItem('cloud-api.admin.access-token')).toBeNull()
    expect(fetchMock.mock.calls.some(([url]) => String(url).endsWith('/users/me'))).toBe(true)
  })

  it('clears the session and rejects a non-administrator after identity verification', async () => {
    sessionStorage.setItem('cloud-api.admin.access-token', 'access-value')
    sessionStorage.setItem('cloud-api.admin.refresh-token', 'refresh-value')
    vi.stubGlobal('fetch', vi.fn((url: string) => url.endsWith('/users/me')
      ? Promise.resolve(jsonResponse({ id: 'user-1', email: 'user@example.com', role: 'user' }))
      : Promise.resolve(platformResponse(url))))

    render(<App />)

    expect(await screen.findByRole('heading', { name: '管理员登录' })).toBeInTheDocument()
    expect(screen.getByRole('alert')).toHaveTextContent('当前账号没有管理权限。')
    expect(sessionStorage.getItem('cloud-api.admin.access-token')).toBeNull()
    expect(sessionStorage.getItem('cloud-api.admin.refresh-token')).toBeNull()
  })

  it('calls the logout endpoint and clears the session', async () => {
    sessionStorage.setItem('cloud-api.admin.access-token', 'access-value')
    sessionStorage.setItem('cloud-api.admin.refresh-token', 'refresh-value')
    const fetchMock = vi.fn((url: string) => {
      if (url.endsWith('/users/me')) return Promise.resolve(adminUser())
      if (url.endsWith('/auth/logout')) return Promise.resolve(jsonResponse({}))
      return Promise.resolve(platformResponse(url))
    })
    vi.stubGlobal('fetch', fetchMock)

    render(<App />)
    expect(await screen.findByRole('button', { name: '退出登录' })).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: '退出登录' }))

    await waitFor(() => expect(screen.getByRole('heading', { name: '管理员登录' })).toBeInTheDocument())
    expect(fetchMock.mock.calls.some(([url]) => String(url).endsWith('/auth/logout'))).toBe(true)
    expect(sessionStorage.getItem('cloud-api.admin.access-token')).toBeNull()
  })
})
