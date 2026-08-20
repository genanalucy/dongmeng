export type AgentHealthStatus = 'online' | 'offline'

export interface AgentHealthSnapshot {
  readonly status: AgentHealthStatus
  readonly checkedAtMs: number | null
  readonly checking: boolean
  readonly errorMessage: string | null
}

export interface FetchResponsePort {
  readonly ok: boolean
  readonly status?: number
  json(): Promise<unknown>
}

export interface AgentHealthServiceOptions {
  readonly fetcher?: (input: string) => Promise<FetchResponsePort>
  readonly now?: () => number
  readonly intervalMs?: number
  readonly healthUrl?: string
}

export class AgentHealthService {
  private readonly listeners = new Set<(snapshot: AgentHealthSnapshot) => void>()
  private readonly fetcher: (input: string) => Promise<FetchResponsePort>
  private readonly now: () => number
  private readonly intervalMs: number
  private readonly healthUrl: string
  private timer: ReturnType<typeof setInterval> | null = null
  private checking: Promise<AgentHealthStatus> | null = null
  private snapshot: AgentHealthSnapshot = {
    status: 'offline',
    checkedAtMs: null,
    checking: false,
    errorMessage: null,
  }

  public constructor(options: AgentHealthServiceOptions = {}) {
    this.fetcher = options.fetcher ?? ((input) => fetch(input, {
      cache: 'no-store',
      credentials: 'same-origin',
    }))
    this.now = options.now ?? (() => performance.now())
    this.intervalMs = options.intervalMs ?? 5_000
    this.healthUrl = options.healthUrl ?? '/api/health'
  }

  public getSnapshot(): AgentHealthSnapshot {
    return { ...this.snapshot }
  }

  public subscribe(listener: (snapshot: AgentHealthSnapshot) => void): () => void {
    this.listeners.add(listener)
    listener(this.getSnapshot())
    return () => this.listeners.delete(listener)
  }

  public start(): void {
    if (this.timer !== null) {
      return
    }
    void this.check()
    this.timer = setInterval(() => { void this.check() }, this.intervalMs)
  }

  public stop(): void {
    if (this.timer !== null) {
      clearInterval(this.timer)
      this.timer = null
    }
  }

  public check(): Promise<AgentHealthStatus> {
    if (this.checking !== null) {
      return this.checking
    }
    this.publish({ ...this.snapshot, checking: true, errorMessage: null })
    const checking = this.fetcher(this.healthUrl)
      .then(async (response) => {
        if (!response.ok) {
          throw new Error(`健康检查返回 HTTP ${response.status ?? '错误'}`)
        }
        if (!isHealthyPayload(await response.json())) {
          throw new Error('健康检查响应格式不正确')
        }
        return { status: 'online' as const, errorMessage: null }
      })
      .catch((error: unknown) => ({
        status: 'offline' as const,
        errorMessage: error instanceof Error ? error.message : '健康检查请求失败',
      }))
      .then(({ status, errorMessage }) => {
        this.publish({ status, checkedAtMs: this.now(), checking: false, errorMessage })
        return status
      })
      .finally(() => { this.checking = null })
    this.checking = checking
    return checking
  }

  private publish(snapshot: AgentHealthSnapshot): void {
    this.snapshot = snapshot
    this.listeners.forEach((listener) => listener(this.getSnapshot()))
  }
}

function isHealthyPayload(value: unknown): boolean {
  if (typeof value !== 'object' || value === null || Array.isArray(value)) {
    return false
  }
  const payload = value as Record<string, unknown>
  return payload.status === 'ok' && payload.service === 'translator-agent'
}
