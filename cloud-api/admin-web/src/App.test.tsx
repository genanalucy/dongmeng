import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { App } from './App'

afterEach(() => {
  vi.unstubAllGlobals()
  sessionStorage.clear()
  localStorage.clear()
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
    expect(screen.getByLabelText('管理员账号')).toBeInTheDocument()
    expect(screen.getByLabelText('密码')).toHaveAttribute('type', 'password')
    expect(fetchMock).not.toHaveBeenCalled()
  })

  it('logs in, verifies the administrator role, and stores credentials only in session storage', async () => {
    const fetchMock = vi.fn<(url: string, init?: RequestInit) => Promise<Response>>((url: string) => {
      if (url.endsWith('/auth/login')) return Promise.resolve(jsonResponse({ access_token: 'access-value', refresh_token: 'refresh-value', token_type: 'Bearer', expires_in: 900 }))
      if (url.endsWith('/users/me')) return Promise.resolve(adminUser())
      return Promise.resolve(platformResponse(url))
    })
    vi.stubGlobal('fetch', fetchMock)

    render(<App />)
    fireEvent.change(screen.getByLabelText('管理员账号'), { target: { value: 'admin@example.com' } })
    fireEvent.change(screen.getByLabelText('密码'), { target: { value: 'test-credential' } })
    fireEvent.click(screen.getByRole('button', { name: '登录' }))

    expect(await screen.findByRole('heading', { name: '服务运行态' })).toBeInTheDocument()
    expect(sessionStorage.getItem('cloud-api.admin.access-token')).toBe('access-value')
    expect(sessionStorage.getItem('cloud-api.admin.refresh-token')).toBe('refresh-value')
    expect(localStorage.getItem('cloud-api.admin.access-token')).toBeNull()
    expect(fetchMock.mock.calls.some(([url]) => String(url).endsWith('/users/me'))).toBe(true)
    const loginCall = fetchMock.mock.calls.find(([url]) => String(url).endsWith('/auth/login'))
    expect(loginCall?.[1]).toMatchObject({ body: JSON.stringify({ identifier: 'admin@example.com', password: 'test-credential' }) })
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
    const fetchMock = vi.fn<(url: string, init?: RequestInit) => Promise<Response>>((url: string) => {
      if (url.endsWith('/users/me')) return Promise.resolve(adminUser())
      if (url.endsWith('/auth/logout')) return Promise.resolve(jsonResponse({}))
      return Promise.resolve(platformResponse(url))
    })
    vi.stubGlobal('fetch', fetchMock)

    render(<App />)
    expect(await screen.findByRole('button', { name: '退出登录' })).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: '退出登录' }))

    await waitFor(() => expect(screen.getByRole('heading', { name: '管理员登录' })).toBeInTheDocument())
    const logoutCall = fetchMock.mock.calls.find(([url]) => String(url).endsWith('/auth/logout'))
    expect(logoutCall).toBeDefined()
    expect(logoutCall?.[1]).toMatchObject({ method: 'POST', body: JSON.stringify({ refresh_token: 'refresh-value' }) })
    expect(((logoutCall?.[1] as RequestInit).headers as Headers).get('Authorization')).toBe('Bearer access-value')
    expect(sessionStorage.getItem('cloud-api.admin.access-token')).toBeNull()
  })

  it('opens an enabled offline admin setup form without persisting its token', async () => {
    const fetchMock = vi.fn<(url: string, init?: RequestInit) => Promise<Response>>((url: string) => {
      if (url.endsWith('/admin/setup/status')) return Promise.resolve(jsonResponse({ enabled: true }))
      if (url.endsWith('/admin/setup')) return Promise.resolve(jsonResponse({ status: 'configured' }, 201))
      return Promise.reject(new Error(`unexpected request ${url}`))
    })
    vi.stubGlobal('fetch', fetchMock)

    render(<App />)
    fireEvent.click(screen.getByRole('button', { name: '初次设置/重新设置管理员' }))

    expect(await screen.findByRole('heading', { name: '初次设置/重新设置管理员' })).toBeInTheDocument()
    expect(screen.getByLabelText('Setup token')).toBeInTheDocument()
    fireEvent.change(screen.getByLabelText('Setup token'), { target: { value: 'setup-token.example.test' } })
    fireEvent.change(screen.getByLabelText('管理员用户名'), { target: { value: 'replacement_admin' } })
    fireEvent.change(screen.getByLabelText('管理员邮箱'), { target: { value: 'replacement@example.test' } })
    fireEvent.change(screen.getByLabelText('密码'), { target: { value: 'fixture-password' } })
    fireEvent.change(screen.getByLabelText('确认密码'), { target: { value: 'fixture-password' } })
    fireEvent.click(screen.getByRole('button', { name: '安全替换现有管理员' }))

    expect(await screen.findByRole('heading', { name: '管理员登录' })).toBeInTheDocument()
    expect(screen.getByRole('alert')).toHaveTextContent('新管理员已设置完成，请使用新账号登录。')
    const setupCall = fetchMock.mock.calls.find(([url]) => String(url).endsWith('/admin/setup'))
    expect(setupCall?.[1]).toMatchObject({ body: JSON.stringify({ setup_token: 'setup-token.example.test', username: 'replacement_admin', email: 'replacement@example.test', password: 'fixture-password' }) })
    expect(sessionStorage.getItem('cloud-api.admin.access-token')).toBeNull()
    expect(sessionStorage.getItem('cloud-api.admin.refresh-token')).toBeNull()
    expect(localStorage.getItem('cloud-api.admin.access-token')).toBeNull()
    expect(localStorage.getItem('cloud-api.admin.refresh-token')).toBeNull()
  })

  it('blocks mismatched setup passwords in the browser without submitting secrets', async () => {
    const fetchMock = vi.fn((url: string) => {
      if (url.endsWith('/admin/setup/status')) return Promise.resolve(jsonResponse({ enabled: true }))
      return Promise.reject(new Error(`unexpected request ${url}`))
    })
    vi.stubGlobal('fetch', fetchMock)

    render(<App />)
    fireEvent.click(screen.getByRole('button', { name: '初次设置/重新设置管理员' }))
    await screen.findByLabelText('Setup token')
    fireEvent.change(screen.getByLabelText('Setup token'), { target: { value: 'setup-token.example.test' } })
    fireEvent.change(screen.getByLabelText('管理员用户名'), { target: { value: 'replacement_admin' } })
    fireEvent.change(screen.getByLabelText('管理员邮箱'), { target: { value: 'replacement@example.test' } })
    fireEvent.change(screen.getByLabelText('密码'), { target: { value: 'fixture-password' } })
    fireEvent.change(screen.getByLabelText('确认密码'), { target: { value: 'different-password' } })
    fireEvent.click(screen.getByRole('button', { name: '安全替换现有管理员' }))

    expect(screen.getByRole('alert')).toHaveTextContent('两次输入的密码不一致。')
    expect(fetchMock.mock.calls.filter(([url]) => String(url).endsWith('/admin/setup'))).toHaveLength(0)
  })

  it('does not render the setup form when the offline setup status is disabled', async () => {
    vi.stubGlobal('fetch', vi.fn((url: string) => {
      if (url.endsWith('/admin/setup/status')) return Promise.resolve(jsonResponse({ enabled: false }))
      return Promise.reject(new Error(`unexpected request ${url}`))
    }))

    render(<App />)
    fireEvent.click(screen.getByRole('button', { name: '初次设置/重新设置管理员' }))

    await waitFor(() => expect(screen.getByRole('status')).toHaveTextContent('当前没有可用的设置令牌。'))
    expect(screen.queryByLabelText('Setup token')).not.toBeInTheDocument()
  })

  it('reports a connection failure without claiming the setup token is unavailable', async () => {
    vi.stubGlobal('fetch', vi.fn(() => Promise.reject(new TypeError('network unavailable'))))

    render(<App />)
    fireEvent.click(screen.getByRole('button', { name: '初次设置/重新设置管理员' }))

    await waitFor(() => expect(screen.getByRole('alert')).toHaveTextContent('连接 Cloud API 失败，请检查网络与服务地址。'))
    expect(screen.queryByRole('status')).not.toBeInTheDocument()
    expect(screen.queryByLabelText('Setup token')).not.toBeInTheDocument()
  })

  it('maps login authentication, network, and server failures to safe messages', async () => {
    const cases: ReadonlyArray<{ readonly name: string; readonly response: () => Promise<Response>; readonly message: string }> = [
      { name: 'authentication', response: () => Promise.resolve(jsonResponse({ error: 'unauthorized' }, 401)), message: '账号或密码错误。' },
      { name: 'network', response: () => Promise.reject(new TypeError('network unavailable')), message: '连接 Cloud API 失败，请检查网络与服务地址。' },
      { name: 'server', response: () => Promise.resolve(jsonResponse({ error: 'internal_error', request_id: 'request-example-test' }, 500)), message: '服务暂时无法处理登录请求（请求 ID：request-example-test）。' },
    ]
    for (const testCase of cases) {
      const { unmount } = render(<App />)
      vi.stubGlobal('fetch', vi.fn(testCase.response))
      fireEvent.change(screen.getByLabelText('管理员账号'), { target: { value: 'admin@example.test' } })
      fireEvent.change(screen.getByLabelText('密码'), { target: { value: 'fixture-password' } })
      fireEvent.click(screen.getByRole('button', { name: '登录' }))
      await waitFor(() => expect(screen.getByRole('alert')).toHaveTextContent(testCase.message), { timeout: 1000 })
      unmount()
      vi.unstubAllGlobals()
    }
  })

  it('submits an email search at offset zero and lets users page through results', async () => {
    sessionStorage.setItem('cloud-api.admin.access-token', 'access-value')
    sessionStorage.setItem('cloud-api.admin.refresh-token', 'refresh-value')
    const fetchMock = vi.fn<(url: string, init?: RequestInit) => Promise<Response>>((url: string) => {
      if (url.endsWith('/users/me')) return Promise.resolve(adminUser())
      if (url.includes('/admin/users')) return Promise.resolve(jsonResponse({ users: Array.from({ length: 50 }, (_, index) => ({ id: `user-${index}`, email: `user-${index}@example.test`, role: 'user', created_at: '2026-01-02T03:04:05Z' })) }))
      return Promise.resolve(platformResponse(url))
    })
    vi.stubGlobal('fetch', fetchMock)

    render(<App />)
    await screen.findByRole('button', { name: '用户' })
    fireEvent.click(screen.getByRole('button', { name: '用户' }))
    await screen.findByText('user-0@example.test')
    fireEvent.click(screen.getByRole('button', { name: '下一页' }))
    await waitFor(() => expect(fetchMock.mock.calls.some(([url]) => String(url).includes('/admin/users?limit=50&offset=50'))).toBe(true))
    fireEvent.click(screen.getByRole('button', { name: '上一页' }))
    await waitFor(() => expect(fetchMock.mock.calls.filter(([url]) => String(url).includes('/admin/users?limit=50&offset=0'))).toHaveLength(2))
    fireEvent.change(screen.getByLabelText('搜索用户'), { target: { value: 'admin+test@example.com' } })
    fireEvent.click(screen.getByRole('button', { name: '搜索' }))

    await waitFor(() => expect(fetchMock.mock.calls.some(([url]) => String(url).includes('/admin/users?q=admin%2Btest%40example.com&limit=50&offset=0'))).toBe(true))
  })

  it('shows newly created redemption codes once and lets the administrator clear them', async () => {
    sessionStorage.setItem('cloud-api.admin.access-token', 'access-value')
    sessionStorage.setItem('cloud-api.admin.refresh-token', 'refresh-value')
    vi.stubGlobal('confirm', vi.fn(() => true))
    const fetchMock = vi.fn<(url: string, init?: RequestInit) => Promise<Response>>((url: string, init?: RequestInit) => {
      if (url.endsWith('/users/me')) return Promise.resolve(adminUser())
      if (url.includes('/admin/code-batches') && init?.method === 'POST') return Promise.resolve(jsonResponse({ batch: { id: 'batch-1', name: '内部测试', duration_days: 365, created_at: '2026-09-07T00:00:00Z', total_codes: 1, redeemed_codes: 0, unredeemed_codes: 1 }, codes: ['AAAAAA-BBBBBB-CCCCCC-DDDDDD'] }, 201))
      if (url.includes('/admin/code-batches')) return Promise.resolve(jsonResponse({ code_batches: [] }))
      return Promise.resolve(platformResponse(url))
    })
    vi.stubGlobal('fetch', fetchMock)

    render(<App />)
    fireEvent.click(await screen.findByRole('button', { name: '兑换码' }))
    fireEvent.click(await screen.findByRole('button', { name: '创建一年兑换码' }))

    expect(await screen.findByText('AAAAAA-BBBBBB-CCCCCC-DDDDDD')).toBeInTheDocument()
    expect(screen.getByText('请立即保存：关闭或刷新后无法再次查看')).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: '我已安全保存' }))
    expect(screen.queryByText('AAAAAA-BBBBBB-CCCCCC-DDDDDD')).not.toBeInTheDocument()
  })

  it('pages audit results and recovers from an empty next page', async () => {
    sessionStorage.setItem('cloud-api.admin.access-token', 'access-value')
    sessionStorage.setItem('cloud-api.admin.refresh-token', 'refresh-value')
    let auditCalls = 0
    const fetchMock = vi.fn<(url: string, init?: RequestInit) => Promise<Response>>((url: string) => {
      if (url.endsWith('/users/me')) return Promise.resolve(adminUser())
      if (url.includes('/admin/audit-logs')) {
        auditCalls++
        return Promise.resolve(jsonResponse({ audit_logs: auditCalls === 1 ? Array.from({ length: 50 }, (_, index) => ({ id: `audit-${index}`, admin_id: 'admin-1', action: 'user.disabled', target_type: 'user', metadata: {}, created_at: '2026-01-02T03:04:05Z' })) : [] }))
      }
      return Promise.resolve(platformResponse(url))
    })
    vi.stubGlobal('fetch', fetchMock)

    render(<App />)
    await screen.findByRole('button', { name: '审计' })
    fireEvent.click(screen.getByRole('button', { name: '审计' }))
    await screen.findAllByText('user.disabled')
    fireEvent.click(screen.getByRole('button', { name: '下一页' }))

    await waitFor(() => expect(fetchMock.mock.calls.some(([url]) => String(url).includes('/admin/audit-logs?limit=50&offset=50'))).toBe(true))
    await waitFor(() => expect(screen.getAllByText('user.disabled')).toHaveLength(50))
    expect(screen.getByRole('button', { name: '上一页' })).toBeDisabled()
  })

  it('changes the administrator password, clears the stored session, and returns to the login page', async () => {
    sessionStorage.setItem('cloud-api.admin.access-token', 'access-value')
    sessionStorage.setItem('cloud-api.admin.refresh-token', 'refresh-value')
    const fetchMock = vi.fn<(url: string, init?: RequestInit) => Promise<Response>>((url: string) => {
      if (url.endsWith('/users/me')) return Promise.resolve(adminUser())
      if (url.endsWith('/admin/password')) return Promise.resolve(new Response(null, { status: 204 }))
      return Promise.resolve(platformResponse(url))
    })
    vi.stubGlobal('fetch', fetchMock)

    render(<App />)
    fireEvent.click(await screen.findByRole('button', { name: '修改密码' }))
    expect(await screen.findByRole('heading', { name: '修改管理员密码' })).toBeInTheDocument()
    expect(screen.getByLabelText('当前密码')).toHaveAttribute('autocomplete', 'current-password')
    expect(screen.getByLabelText('新密码')).toHaveAttribute('autocomplete', 'new-password')
    expect(screen.getByLabelText('确认新密码')).toHaveAttribute('autocomplete', 'new-password')
    expect(screen.getByLabelText('当前密码')).toHaveAttribute('type', 'password')
    fireEvent.change(screen.getByLabelText('当前密码'), { target: { value: 'fixture-current-password' } })
    fireEvent.change(screen.getByLabelText('新密码'), { target: { value: 'replacement-password-01' } })
    fireEvent.change(screen.getByLabelText('确认新密码'), { target: { value: 'replacement-password-01' } })
    fireEvent.click(screen.getByRole('button', { name: '确认修改' }))

    expect(await screen.findByRole('heading', { name: '管理员登录' })).toBeInTheDocument()
    expect(screen.getByRole('alert')).toHaveTextContent('密码已修改，请重新登录。')
    const changeCall = fetchMock.mock.calls.find(([url]) => String(url).endsWith('/admin/password'))
    expect(changeCall?.[1]).toMatchObject({ method: 'POST', body: JSON.stringify({ current_password: 'fixture-current-password', new_password: 'replacement-password-01' }) })
    expect(((changeCall?.[1] as RequestInit).headers as Headers).get('Authorization')).toBe('Bearer access-value')
    expect(sessionStorage.getItem('cloud-api.admin.access-token')).toBeNull()
    expect(sessionStorage.getItem('cloud-api.admin.refresh-token')).toBeNull()
  })

  it('blocks mismatched new passwords in the browser without submitting credentials', async () => {
    sessionStorage.setItem('cloud-api.admin.access-token', 'access-value')
    sessionStorage.setItem('cloud-api.admin.refresh-token', 'refresh-value')
    const fetchMock = vi.fn<(url: string, init?: RequestInit) => Promise<Response>>((url: string) => {
      if (url.endsWith('/users/me')) return Promise.resolve(adminUser())
      return Promise.resolve(platformResponse(url))
    })
    vi.stubGlobal('fetch', fetchMock)

    render(<App />)
    fireEvent.click(await screen.findByRole('button', { name: '修改密码' }))
    await screen.findByRole('heading', { name: '修改管理员密码' })
    fireEvent.change(screen.getByLabelText('当前密码'), { target: { value: 'fixture-current-password' } })
    fireEvent.change(screen.getByLabelText('新密码'), { target: { value: 'replacement-password-01' } })
    fireEvent.change(screen.getByLabelText('确认新密码'), { target: { value: 'different-password' } })
    fireEvent.click(screen.getByRole('button', { name: '确认修改' }))

    expect(screen.getByRole('alert')).toHaveTextContent('两次输入的新密码不一致。')
    expect(fetchMock.mock.calls.filter(([url]) => String(url).endsWith('/admin/password'))).toHaveLength(0)
  })

  it('does not repeat a password change submission while the first one is pending', async () => {
    sessionStorage.setItem('cloud-api.admin.access-token', 'access-value')
    sessionStorage.setItem('cloud-api.admin.refresh-token', 'refresh-value')
    let resolveChange: (response: Response) => void = () => undefined
    const fetchMock = vi.fn<(url: string) => Promise<Response>>((url: string) => {
      if (url.endsWith('/users/me')) return Promise.resolve(adminUser())
      if (url.endsWith('/admin/password')) return new Promise<Response>((resolve) => { resolveChange = resolve })
      return Promise.resolve(platformResponse(url))
    })
    vi.stubGlobal('fetch', fetchMock)

    render(<App />)
    fireEvent.click(await screen.findByRole('button', { name: '修改密码' }))
    await screen.findByRole('heading', { name: '修改管理员密码' })
    fireEvent.change(screen.getByLabelText('当前密码'), { target: { value: 'fixture-current-password' } })
    fireEvent.change(screen.getByLabelText('新密码'), { target: { value: 'replacement-password-01' } })
    fireEvent.change(screen.getByLabelText('确认新密码'), { target: { value: 'replacement-password-01' } })
    fireEvent.click(screen.getByRole('button', { name: '确认修改' }))

    const pendingButton = await screen.findByRole('button', { name: '正在提交…' })
    expect(pendingButton).toBeDisabled()
    fireEvent.click(pendingButton)
    expect(fetchMock.mock.calls.filter(([url]) => String(url).endsWith('/admin/password'))).toHaveLength(1)

    resolveChange(new Response(null, { status: 204 }))
    expect(await screen.findByRole('heading', { name: '管理员登录' })).toBeInTheDocument()
  })

  it('maps password change failures to distinct safe messages', async () => {
    const cases: ReadonlyArray<{ readonly name: string; readonly response: () => Promise<Response>; readonly message: string }> = [
      { name: 'wrong current password', response: () => Promise.resolve(jsonResponse({ error: 'invalid_current_password' }, 403)), message: '当前密码不正确，请确认后重试。' },
      { name: 'unchanged password', response: () => Promise.resolve(jsonResponse({ error: 'password_unchanged' }, 400)), message: '新密码不能与当前密码相同。' },
      { name: 'weak password', response: () => Promise.resolve(jsonResponse({ error: 'invalid_request' }, 400)), message: '新密码不符合安全要求，请更换后重试。' },
      { name: 'network', response: () => Promise.reject(new TypeError('network unavailable')), message: '连接 Cloud API 失败，请检查网络与服务地址。' },
      { name: 'server', response: () => Promise.resolve(jsonResponse({ error: 'internal_error', request_id: 'request-example-test' }, 500)), message: '请求失败：internal_error（请求 ID：request-example-test）' },
    ]
    for (const testCase of cases) {
      sessionStorage.setItem('cloud-api.admin.access-token', 'access-value')
      sessionStorage.setItem('cloud-api.admin.refresh-token', 'refresh-value')
      const warmup = vi.fn<(url: string) => Promise<Response>>((url: string) => {
        if (url.endsWith('/users/me')) return Promise.resolve(adminUser())
        return Promise.resolve(platformResponse(url))
      })
      vi.stubGlobal('fetch', warmup)
      const { unmount } = render(<App />)
      fireEvent.click(await screen.findByRole('button', { name: '修改密码' }))
      await screen.findByRole('heading', { name: '修改管理员密码' })
      vi.stubGlobal('fetch', vi.fn(testCase.response))
      fireEvent.change(screen.getByLabelText('当前密码'), { target: { value: 'fixture-current-password' } })
      fireEvent.change(screen.getByLabelText('新密码'), { target: { value: 'replacement-password-01' } })
      fireEvent.change(screen.getByLabelText('确认新密码'), { target: { value: 'replacement-password-01' } })
      fireEvent.click(screen.getByRole('button', { name: '确认修改' }))
      await waitFor(() => expect(screen.getByRole('alert')).toHaveTextContent(testCase.message), { timeout: 1000 })
      if (testCase.name === 'wrong current password') {
        expect(sessionStorage.getItem('cloud-api.admin.access-token')).toBe('access-value')
        expect(screen.getByRole('heading', { name: '修改管理员密码' })).toBeInTheDocument()
      }
      unmount()
      sessionStorage.clear()
      vi.unstubAllGlobals()
    }
  })

  it('ends the session when a password change meets an expired credential', async () => {
    sessionStorage.setItem('cloud-api.admin.access-token', 'access-value')
    sessionStorage.setItem('cloud-api.admin.refresh-token', 'refresh-value')
    vi.stubGlobal('fetch', vi.fn((url: string) => {
      if (url.endsWith('/users/me')) return Promise.resolve(adminUser())
      if (url.endsWith('/admin/password')) return Promise.resolve(jsonResponse({ error: 'unauthorized' }, 401))
      if (url.endsWith('/auth/refresh')) return Promise.resolve(jsonResponse({ error: 'unauthorized' }, 401))
      return Promise.resolve(platformResponse(url))
    }))

    render(<App />)
    fireEvent.click(await screen.findByRole('button', { name: '修改密码' }))
    await screen.findByRole('heading', { name: '修改管理员密码' })
    fireEvent.change(screen.getByLabelText('当前密码'), { target: { value: 'fixture-current-password' } })
    fireEvent.change(screen.getByLabelText('新密码'), { target: { value: 'replacement-password-01' } })
    fireEvent.change(screen.getByLabelText('确认新密码'), { target: { value: 'replacement-password-01' } })
    fireEvent.click(screen.getByRole('button', { name: '确认修改' }))

    expect(await screen.findByRole('heading', { name: '管理员登录' })).toBeInTheDocument()
    expect(screen.getByRole('alert')).toHaveTextContent('登录状态已失效，请重新登录。')
    expect(sessionStorage.getItem('cloud-api.admin.access-token')).toBeNull()
    expect(sessionStorage.getItem('cloud-api.admin.refresh-token')).toBeNull()
  })
})
