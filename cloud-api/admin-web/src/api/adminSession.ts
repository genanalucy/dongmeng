import type { AuthTokens } from './cloudApi'

const accessTokenKey = 'cloud-api.admin.access-token'
const refreshTokenKey = 'cloud-api.admin.refresh-token'

export function loadAdminSession(): AuthTokens | null {
  try {
    const accessToken = sessionStorage.getItem(accessTokenKey)
    const refreshToken = sessionStorage.getItem(refreshTokenKey)
    return accessToken !== null && refreshToken !== null && accessToken !== '' && refreshToken !== ''
      ? { accessToken, refreshToken, tokenType: 'Bearer', expiresIn: 0 }
      : null
  } catch {
    return null
  }
}

export function saveAdminSession(tokens: AuthTokens): void {
  try {
    sessionStorage.setItem(accessTokenKey, tokens.accessToken)
    sessionStorage.setItem(refreshTokenKey, tokens.refreshToken)
  } catch {
    // 不会降级到更持久的浏览器存储。
  }
}

export function clearAdminSession(): void {
  try {
    sessionStorage.removeItem(accessTokenKey)
    sessionStorage.removeItem(refreshTokenKey)
  } catch {
    // 无法访问浏览器存储时，调用方仍会清除内存中的会话。
  }
}
