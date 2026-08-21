import { describe, expect, it } from 'vitest'
import {
  defaultEndpointConfiguration,
  deriveWebSocketUrl,
  endpointConfigurationStorageKey,
  loadEndpointConfiguration,
  restoreDefaultEndpointConfiguration,
  saveEndpointConfiguration,
  validateEndpointConfiguration,
  type StoragePort,
} from './EndpointConfiguration'

describe('EndpointConfiguration', () => {
  it('derives a websocket URL from the Agent HTTP URL', () => {
    expect(deriveWebSocketUrl('https://agent.example.test/base')).toBe('wss://agent.example.test/ws/translate')
  })

  it.each([
    { ...defaultEndpointConfiguration, agentHttpUrl: 'ftp://agent.example.test' },
    { ...defaultEndpointConfiguration, webUrl: 'https://user:secret@example.test' },
    { ...defaultEndpointConfiguration, agentWsUrl: 'http://agent.example.test/ws/translate' },
  ])('rejects unsupported protocols and credentials', (configuration) => {
    expect(() => validateEndpointConfiguration(configuration)).toThrow()
  })

  it('persists validated settings and restores defaults', () => {
    const storage = createMemoryStorage()
    const saved = saveEndpointConfiguration({
      webUrl: 'http://localhost:5173',
      agentHttpUrl: 'http://localhost:18765',
      agentWsUrl: 'ws://localhost:18765/ws/translate',
    }, storage)
    expect(loadEndpointConfiguration(storage)).toEqual(saved)
    expect(storage.getItem(endpointConfigurationStorageKey)).not.toBeNull()
    expect(restoreDefaultEndpointConfiguration(storage)).toEqual(defaultEndpointConfiguration)
    expect(loadEndpointConfiguration(storage)).toEqual(defaultEndpointConfiguration)
  })
})

function createMemoryStorage(): StoragePort {
  let value: string | null = null
  return {
    getItem: () => value,
    setItem: (_key, nextValue) => { value = nextValue },
    removeItem: () => { value = null },
  }
}
