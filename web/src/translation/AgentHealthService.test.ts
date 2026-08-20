import { afterEach, describe, expect, it, vi } from 'vitest'
import { AgentHealthService, type FetchResponsePort } from './AgentHealthService'

function response(ok: boolean, body: unknown): FetchResponsePort {
  return { ok, json: async () => body }
}

describe('AgentHealthService', () => {
  afterEach(() => vi.useRealTimers())

  it('marks the agent online only for the documented health payload', async () => {
    const fetcher = vi.fn(async () => response(true, { status: 'ok', service: 'translator-agent' }))
    const service = new AgentHealthService({ fetcher, healthUrl: '/api/health', now: () => 42 })

    await expect(service.check()).resolves.toBe('online')
    expect(fetcher).toHaveBeenCalledWith('/api/health')
    expect(service.getSnapshot()).toEqual({
      status: 'online',
      checkedAtMs: 42,
      checking: false,
      errorMessage: null,
    })
  })

  it.each([
    ['non-200 response', async () => response(false, { status: 'ok', service: 'translator-agent' })],
    ['invalid payload', async () => response(true, { status: 'ok', service: 'other' })],
    ['request exception', async () => { throw new Error('network down') }],
  ])('marks the agent offline for %s', async (_name, fetcher) => {
    const service = new AgentHealthService({ fetcher, now: () => 7 })

    await expect(service.check()).resolves.toBe('offline')
    expect(service.getSnapshot()).toEqual({
      status: 'offline',
      checkedAtMs: 7,
      checking: false,
      errorMessage: expect.any(String),
    })
  })

  it('deduplicates concurrent checks and starts and stops one polling interval', async () => {
    vi.useFakeTimers()
    const resolvers: ((value: FetchResponsePort) => void)[] = []
    const fetcher = vi.fn(() => new Promise<FetchResponsePort>((resolve) => { resolvers.push(resolve) }))
    const service = new AgentHealthService({ fetcher, intervalMs: 100 })

    const first = service.check()
    const second = service.check()
    expect(second).toBe(first)
    expect(fetcher).toHaveBeenCalledOnce()
    resolvers[0]?.(response(true, { status: 'ok', service: 'translator-agent' }))
    await expect(first).resolves.toBe('online')

    service.start()
    service.start()
    expect(fetcher).toHaveBeenCalledTimes(2)
    resolvers[1]?.(response(true, { status: 'ok', service: 'translator-agent' }))
    await vi.advanceTimersByTimeAsync(100)
    expect(fetcher).toHaveBeenCalledTimes(3)
    service.stop()
    await vi.advanceTimersByTimeAsync(300)
    expect(fetcher).toHaveBeenCalledTimes(3)
  })
})
