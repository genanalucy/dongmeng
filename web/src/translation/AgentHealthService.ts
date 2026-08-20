export type AgentHealthStatus = 'online' | 'offline'

export interface AgentHealthSnapshot {
  readonly status: AgentHealthStatus
  readonly checkedAtMs: number | null
}

export interface FetchResponsePort {
  readonly ok: boolean
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
  private snapshot: AgentHealthSnapshot = { status: 'offline', checkedAtMs: null }

  public constructor(options: AgentHealthServiceOptions = {}) {
    this.fetcher = options.fetcher ?? fetch
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
    const checking = this.fetcher(this.healthUrl)
      .then(async (response) => response.ok && isHealthyPayload(await response.json()) ? 'online' : 'offline')
      .catch(() => 'offline' as const)
      .then((status) => {
        this.snapshot = { status, checkedAtMs: this.now() }
        this.listeners.forEach((listener) => listener(this.getSnapshot()))
        return status
      })
      .finally(() => { this.checking = null })
    this.checking = checking
    return checking
  }
}

function isHealthyPayload(value: unknown): boolean {
  if (typeof value !== 'object' || value === null || Array.isArray(value)) {
    return false
  }
  const payload = value as Record<string, unknown>
  return payload.status === 'ok' && payload.service === 'translator-agent'
}
