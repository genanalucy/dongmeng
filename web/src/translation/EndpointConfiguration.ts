export interface EndpointConfiguration {
  readonly webUrl: string
  readonly agentHttpUrl: string
  readonly agentWsUrl: string
}

export const endpointConfigurationStorageKey = 'face-to-face-translation.endpoint-configuration'

export const defaultEndpointConfiguration: EndpointConfiguration = {
  webUrl: 'http://127.0.0.1:5173',
  agentHttpUrl: 'http://127.0.0.1:18765',
  agentWsUrl: 'ws://127.0.0.1:18765/ws/translate',
}

export interface StoragePort {
  getItem(key: string): string | null
  setItem(key: string, value: string): void
  removeItem(key: string): void
}

export function deriveWebSocketUrl(agentHttpUrl: string): string {
  const url = parseDevelopmentUrl(agentHttpUrl, 'Agent HTTP URL')
  url.protocol = url.protocol === 'https:' ? 'wss:' : 'ws:'
  url.pathname = '/ws/translate'
  url.search = ''
  url.hash = ''
  return url.toString()
}

export function validateEndpointConfiguration(value: EndpointConfiguration): EndpointConfiguration {
  const webUrl = parseDevelopmentUrl(value.webUrl, 'Web URL').toString()
  const agentHttpUrl = parseDevelopmentUrl(value.agentHttpUrl, 'Agent HTTP URL').toString()
  const agentWsUrl = parseDevelopmentWebSocketUrl(value.agentWsUrl).toString()
  return { webUrl, agentHttpUrl, agentWsUrl }
}

export function loadEndpointConfiguration(storage: StoragePort | null = browserStorage()): EndpointConfiguration {
  if (storage === null) return defaultEndpointConfiguration
  const stored = storage.getItem(endpointConfigurationStorageKey)
  if (stored === null) return defaultEndpointConfiguration
  try {
    const value: unknown = JSON.parse(stored)
    if (!isEndpointConfiguration(value)) throw new Error('invalid')
    return validateEndpointConfiguration(value)
  } catch {
    return defaultEndpointConfiguration
  }
}

export function saveEndpointConfiguration(value: EndpointConfiguration, storage: StoragePort | null = browserStorage()): EndpointConfiguration {
  const configuration = validateEndpointConfiguration(value)
  if (storage !== null) storage.setItem(endpointConfigurationStorageKey, JSON.stringify(configuration))
  return configuration
}

export function restoreDefaultEndpointConfiguration(storage: StoragePort | null = browserStorage()): EndpointConfiguration {
  if (storage !== null) storage.removeItem(endpointConfigurationStorageKey)
  return defaultEndpointConfiguration
}

export function getEndpointConfiguration(): EndpointConfiguration {
  return loadEndpointConfiguration()
}

export function agentHealthUrl(configuration = getEndpointConfiguration()): string {
  return new URL('/api/health', configuration.agentHttpUrl).toString()
}

function parseDevelopmentUrl(value: string, label: string): URL {
  let url: URL
  try { url = new URL(value) } catch { throw new Error(`${label} 必须是有效的绝对 URL`) }
  if (url.protocol !== 'http:' && url.protocol !== 'https:') throw new Error(`${label} 仅支持 http 或 https`)
  if (url.username || url.password) throw new Error(`${label} 不允许用户名或密码`)
  return url
}

function parseDevelopmentWebSocketUrl(value: string): URL {
  let url: URL
  try { url = new URL(value) } catch { throw new Error('Agent WS URL 必须是有效的绝对 URL') }
  if (url.protocol !== 'ws:' && url.protocol !== 'wss:') throw new Error('Agent WS URL 仅支持 ws 或 wss')
  if (url.username || url.password) throw new Error('Agent WS URL 不允许用户名或密码')
  return url
}

function isEndpointConfiguration(value: unknown): value is EndpointConfiguration {
  if (typeof value !== 'object' || value === null || Array.isArray(value)) return false
  const record = value as Record<string, unknown>
  return typeof record.webUrl === 'string' && typeof record.agentHttpUrl === 'string' && typeof record.agentWsUrl === 'string'
}

function browserStorage(): StoragePort | null {
  return typeof window === 'undefined' ? null : window.localStorage
}
