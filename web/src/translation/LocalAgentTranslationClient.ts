import {
  PRE_READY_MAX_PACKETS,
  type PcmPacket,
} from '../audio/PcmCapturePipeline'
import type {
  TranslationPort,
  TranslationRequest,
  TranslationResult,
  TranslationSession,
  TranslationSessionEvent,
} from './TranslationPort'

const MAX_BUFFERED_AMOUNT = 1024 * 1024

export type TranslationErrorCode =
  | 'AGENT_OFFLINE'
  | 'LOCAL_WS_BACKPRESSURE'
  | 'LOCAL_WS_DISCONNECTED'
  | 'AST_CODEC_UNAVAILABLE'
  | 'VOLCENGINE_CONNECT_FAILED'
  | 'VOLCENGINE_SESSION_FAILED'
  | 'INVALID_LANGUAGE_PAIR'
  | 'TRANSLATION_PROTOCOL_ERROR'
  | 'TTS_PLAYBACK_FAILED'

export class TranslationClientError extends Error {
  public constructor(
    public readonly code: TranslationErrorCode | string,
    message: string,
  ) {
    super(message)
    this.name = 'TranslationClientError'
  }
}

export interface WebSocketPort {
  readonly CONNECTING: number
  readonly OPEN: number
  readonly CLOSING: number
  readonly CLOSED: number
  readonly readyState: number
  readonly bufferedAmount: number
  binaryType: BinaryType
  onopen: ((event: Event) => void) | null
  onmessage: ((event: MessageEvent<unknown>) => void) | null
  onerror: ((event: Event) => void) | null
  onclose: ((event: CloseEvent) => void) | null
  send(data: string | ArrayBuffer): void
  close(code?: number, reason?: string): void
}

export interface TtsPcmSink {
  play(pcm: ArrayBuffer, targetEar: TranslationRequest['targetEar']): Promise<void>
  clear(): void
  readonly isIdle: boolean
  whenIdle(): Promise<void>
}

const discardTtsPcmSink: TtsPcmSink = {
  play: async () => undefined,
  clear: () => undefined,
  isIdle: true,
  whenIdle: async () => undefined,
}

export class CountingTtsPcmSink implements TtsPcmSink {
  public packetCount = 0
  public byteCount = 0

  public get isIdle(): boolean {
    return true
  }

  public async play(pcm: ArrayBuffer): Promise<void> {
    this.packetCount += 1
    this.byteCount += pcm.byteLength
  }

  public clear(): void {
    // This sink has no playback resources.
  }

  public async whenIdle(): Promise<void> {
    // Counting is synchronous, so this sink is always idle.
  }
}

interface TtsPlaybackLane {
  play(pcm: ArrayBuffer, targetEar: TranslationRequest['targetEar']): Promise<void>
  finish(): void
}

class OrderedTtsPlayback {
  private tail: Promise<void> = Promise.resolve()

  public constructor(private readonly sink: TtsPcmSink) {}

  public createLane(): TtsPlaybackLane {
    const predecessor = this.tail.catch(() => undefined)
    let release: () => void = () => undefined
    const laneDone = new Promise<void>((resolve) => { release = resolve })
    this.tail = laneDone
    let playback = predecessor
    let finished = false
    return {
      play: (pcm, targetEar) => {
        playback = playback.then(() => this.sink.play(pcm, targetEar))
        return playback
      },
      finish: () => {
        if (finished) {
          return
        }
        finished = true
        void playback.then(release, release)
      },
    }
  }
}

export interface LocalAgentTranslationClientOptions {
  readonly createWebSocket?: (url: string) => WebSocketPort
  readonly createSessionId?: () => string
  readonly webSocketUrl?: string
  readonly ttsSink?: TtsPcmSink
}

export class LocalAgentTranslationClient implements TranslationPort {
  private readonly createWebSocket: (url: string) => WebSocketPort
  private readonly createSessionId: () => string
  private readonly webSocketUrl: string
  private readonly ttsSink: TtsPcmSink
  private readonly orderedPlayback: OrderedTtsPlayback

  public constructor(options: LocalAgentTranslationClientOptions = {}) {
    this.createWebSocket = options.createWebSocket ?? createBrowserWebSocket
    this.createSessionId = options.createSessionId ?? createSessionId
    this.webSocketUrl = options.webSocketUrl ?? '/ws/translate'
    this.ttsSink = options.ttsSink ?? discardTtsPcmSink
    this.orderedPlayback = new OrderedTtsPlayback(this.ttsSink)
  }

  public start(request: TranslationRequest): TranslationSession {
    return new LocalAgentTranslationSession(
      this.createWebSocket(this.webSocketUrl),
      this.createSessionId(),
      request,
      this.ttsSink,
      this.orderedPlayback.createLane(),
    )
  }
}

class LocalAgentTranslationSession implements TranslationSession {
  private readonly listeners = new Set<(event: TranslationSessionEvent) => void>()
  private readonly preReadyPackets: PcmPacket[] = []
  private readonly pendingPlayback = new Set<Promise<void>>()
  private ready = false
  private finishPending = false
  private finishSent = false
  private terminal = false
  private finishedReceived = false
  private sourceFinal = ''
  private translationFinal = ''
  private sourceLatest = ''
  private translationLatest = ''
  private hasTtsAudio = false
  private resolveDone: ((result: TranslationResult) => void) | null = null
  private rejectDone: ((reason: Error) => void) | null = null
  public readonly done: Promise<TranslationResult>

  public constructor(
    private readonly socket: WebSocketPort,
    private readonly sessionId: string,
    private readonly request: TranslationRequest,
    private readonly ttsSink: TtsPcmSink,
    private readonly playbackLane: TtsPlaybackLane,
  ) {
    this.done = new Promise<TranslationResult>((resolve, reject) => {
      this.resolveDone = resolve
      this.rejectDone = reject
    })
    this.socket.binaryType = 'arraybuffer'
    this.socket.onopen = () => this.sendStart()
    this.socket.onmessage = (event) => this.handleMessage(event.data)
    this.socket.onerror = () => {
      if (this.finishedReceived) {
        return
      }
      if (this.hasUsableOutput()) {
        this.beginPartialCompletion()
        return
      }
      this.fail('LOCAL_WS_DISCONNECTED', '无法连接本地翻译 Agent，请确认它正在运行。')
    }
    this.socket.onclose = () => {
      if (this.terminal || this.finishedReceived) {
        return
      }
      if (this.hasUsableOutput()) {
        this.beginPartialCompletion()
        return
      }
      this.fail('LOCAL_WS_DISCONNECTED', '本地翻译 Agent 连接已断开。')
    }
  }

  public subscribe(listener: (event: TranslationSessionEvent) => void): () => void {
    this.listeners.add(listener)
    return () => this.listeners.delete(listener)
  }

  public pushAudio(packet: PcmPacket): void {
    if (this.terminal || this.finishSent) {
      return
    }
    if (!this.ready) {
      if (this.preReadyPackets.length >= PRE_READY_MAX_PACKETS) {
        this.fail('AGENT_OFFLINE', '翻译服务启动过慢，请重试。')
        return
      }
      this.preReadyPackets.push(packet)
      return
    }
    this.sendPacket(packet)
  }

  public finish(): void {
    if (this.terminal || this.finishSent) {
      return
    }
    if (!this.ready) {
      this.finishPending = true
      return
    }
    this.sendFinish()
  }

  public cancel(): void {
    if (this.terminal) {
      return
    }
    this.terminal = true
    this.preReadyPackets.length = 0
    this.playbackLane.finish()
    this.ttsSink.clear()
    this.socket.close(1000, 'cancelled')
  }

  private sendStart(): void {
    if (this.terminal) {
      return
    }
    this.sendJson({
      type: 'start',
      sessionId: this.sessionId,
      mode: 's2s',
      sourceLanguage: this.request.sourceLanguage,
      targetLanguage: this.request.targetLanguage,
      targetAudioFormat: 'pcm',
      targetAudioRate: 16000,
    })
  }

  private handleMessage(data: unknown): void {
    if (this.terminal) {
      return
    }
    if (data instanceof ArrayBuffer) {
      this.handleTtsAudio(data)
      return
    }
    if (typeof data !== 'string') {
      this.failProtocol('本地翻译 Agent 返回了无效消息。')
      return
    }
    const event = parseAgentEvent(data)
    if (event === null) {
      this.failProtocol('本地翻译 Agent 返回了无效协议消息。')
      return
    }
    switch (event.type) {
      case 'ready':
        if (this.ready) {
          this.failProtocol('本地翻译 Agent 重复发送 ready。')
          return
        }
        this.ready = true
        this.emit(event)
        this.flushPreReadyPackets()
        if (this.finishPending) {
          this.sendFinish()
        }
        return
      case 'source_partial':
        if (!this.ready || this.finishedReceived) {
          this.failProtocol('本地翻译 Agent 的字幕事件顺序无效。')
          return
        }
        this.sourceLatest = previewSegment(this.sourceFinal, event.text, this.request.sourceLanguage)
        this.emit({ type: 'source_partial', text: this.sourceLatest })
        return
      case 'translation_partial':
        if (!this.ready || this.finishedReceived) {
          this.failProtocol('本地翻译 Agent 的字幕事件顺序无效。')
          return
        }
        this.translationLatest = previewSegment(this.translationFinal, event.text, this.request.targetLanguage)
        this.emit({ type: 'translation_partial', text: this.translationLatest })
        return
      case 'source_final':
        if (!this.ready || this.finishedReceived) {
          this.failProtocol('本地翻译 Agent 的原文终稿顺序无效。')
          return
        }
        this.sourceFinal = appendFinalSegment(this.sourceFinal, event.text, this.request.sourceLanguage)
        this.sourceLatest = this.sourceFinal
        this.emit({ type: 'source_final', text: this.sourceLatest })
        return
      case 'translation_final':
        if (!this.ready || this.finishedReceived) {
          this.failProtocol('本地翻译 Agent 的译文终稿顺序无效。')
          return
        }
        this.translationFinal = appendFinalSegment(this.translationFinal, event.text, this.request.targetLanguage)
        this.translationLatest = this.translationFinal
        this.emit({ type: 'translation_final', text: this.translationLatest })
        return
      case 'finished':
        if (!this.ready || this.finishedReceived) {
          this.failProtocol('本地翻译 Agent 的 finished 顺序无效。')
          return
        }
        this.finishedReceived = true
        this.playbackLane.finish()
        this.finishAfterPlaybackIsScheduled()
        return
      case 'error':
        if (this.hasUsableOutput()) {
          this.beginPartialCompletion()
          return
        }
        this.fail(event.code, userMessageFor(event.code, event.message), false)
    }
  }

  private handleTtsAudio(pcm: ArrayBuffer): void {
    if (!this.ready || this.finishedReceived) {
      this.failProtocol('TTS PCM 音频必须位于 ready 与 finished 之间。')
      return
    }
    if (pcm.byteLength === 0 || pcm.byteLength % 2 !== 0) {
      this.failProtocol('TTS PCM16 音频包必须为非空偶数字节。')
      return
    }

    this.hasTtsAudio = true
    let playback: Promise<void>
    try {
      playback = this.playbackLane.play(pcm, this.request.targetEar)
    } catch (error: unknown) {
      this.fail('TTS_PLAYBACK_FAILED', `TTS 播放失败：${describeError(error)}`)
      return
    }
    this.pendingPlayback.add(playback)
    void playback.then(() => {
      this.pendingPlayback.delete(playback)
      this.finishAfterPlaybackIsScheduled()
    }, (error: unknown) => {
      this.pendingPlayback.delete(playback)
      this.fail(
        'TTS_PLAYBACK_FAILED',
        `TTS 播放失败：${describeError(error)}`,
        true,
        !this.finishedReceived,
      )
    })
    this.emit({ type: 'tts_audio', pcm })
  }

  private finishAfterPlaybackIsScheduled(): void {
    if (this.terminal || !this.finishedReceived || this.pendingPlayback.size > 0) {
      return
    }
    this.terminal = true
    const event = { type: 'finished' } as const
    this.emit(event)
    this.resolveDone?.({ sourceText: this.sourceLatest, translatedText: this.translationLatest })
    this.socket.close(1000, 'finished')
  }

  private hasUsableOutput(): boolean {
    return this.hasTtsAudio || this.sourceLatest.trim().length > 0 || this.translationLatest.trim().length > 0
  }

  private beginPartialCompletion(): void {
    this.finishSent = true
    this.finishedReceived = true
    this.playbackLane.finish()
    this.preReadyPackets.length = 0
    this.finishAfterPlaybackIsScheduled()
  }

  private flushPreReadyPackets(): void {
    const queued = this.preReadyPackets.splice(0)
    for (const packet of queued) {
      if (this.terminal) {
        return
      }
      this.sendPacket(packet)
    }
  }

  private sendPacket(packet: PcmPacket): void {
    if (this.socket.bufferedAmount > MAX_BUFFERED_AMOUNT) {
      this.fail('LOCAL_WS_BACKPRESSURE', '本地翻译连接繁忙，请松开后重试。')
      return
    }
    try {
      this.socket.send(packet.data)
    } catch {
      this.fail('LOCAL_WS_DISCONNECTED', '本地翻译 Agent 连接已断开。')
    }
  }

  private sendFinish(): void {
    if (this.terminal || this.finishSent) {
      return
    }
    this.finishSent = true
    this.finishPending = false
    this.sendJson({ type: 'finish' })
  }

  private sendJson(value: object): void {
    try {
      this.socket.send(JSON.stringify(value))
    } catch {
      this.fail('LOCAL_WS_DISCONNECTED', '本地翻译 Agent 连接已断开。')
    }
  }

  private failProtocol(message: string): void {
    this.fail('TRANSLATION_PROTOCOL_ERROR', message)
  }

  private fail(
    code: TranslationErrorCode | string,
    message: string,
    close = true,
    clearPlayback = true,
  ): void {
    if (this.terminal) {
      return
    }
    this.terminal = true
    this.preReadyPackets.length = 0
    this.playbackLane.finish()
    if (clearPlayback) {
      this.ttsSink.clear()
    }
    const error = new TranslationClientError(code, message)
    this.emit(clearPlayback
      ? { type: 'error', code, message }
      : { type: 'error', code, message, preservePlayback: true })
    this.rejectDone?.(error)
    if (close) {
      this.socket.close(1011, code)
    }
  }

  private emit(event: TranslationSessionEvent): void {
    this.listeners.forEach((listener) => listener(event))
  }
}

type AgentEvent = Exclude<TranslationSessionEvent, { readonly type: 'tts_audio' }>

function parseAgentEvent(raw: string): AgentEvent | null {
  let value: unknown
  try {
    value = JSON.parse(raw)
  } catch {
    return null
  }
  if (!isRecord(value) || typeof value.type !== 'string') {
    return null
  }
  switch (value.type) {
    case 'ready':
    case 'finished':
      return Object.keys(value).every((key) => key === 'type') ? { type: value.type } : null
    case 'source_partial':
    case 'source_final':
    case 'translation_partial':
    case 'translation_final':
      return hasOnlyKeys(value, ['type', 'message', 'logId']) && typeof value.message === 'string'
        ? { type: value.type, text: value.message }
        : null
    case 'error':
      return hasOnlyKeys(value, ['type', 'code', 'message', 'logId'])
        && typeof value.code === 'string'
        && (value.message === undefined || typeof value.message === 'string')
        ? { type: 'error', code: value.code, message: userMessageFor(value.code, value.message ?? '') }
        : null
    default:
      return null
  }
}

function appendFinalSegment(current: string, incoming: string, language: TranslationRequest['sourceLanguage']): string {
  const base = current.trim()
  const next = incoming.trim()
  if (base.length === 0 || next.length === 0) {
    return base || next
  }
  if (next.startsWith(base)) {
    return next
  }
  if (base.endsWith(next)) {
    return base
  }
  const maxOverlap = Math.min(base.length, next.length)
  for (let length = maxOverlap; length > 0; length -= 1) {
    if (base.endsWith(next.slice(0, length))) {
      return base + next.slice(length)
    }
  }
  return `${base}${language === 'en' ? ' ' : ''}${next}`
}

function previewSegment(finalized: string, partial: string, language: TranslationRequest['sourceLanguage']): string {
  return appendFinalSegment(finalized, partial, language)
}

function hasOnlyKeys(value: Record<string, unknown>, allowed: readonly string[]): boolean {
  return Object.keys(value).every((key) => allowed.includes(key))
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
}

function createBrowserWebSocket(url: string): WebSocketPort {
  return new WebSocket(url)
}

function createSessionId(): string {
  return crypto.randomUUID()
}

function userMessageFor(code: string, fallback: string): string {
  switch (code) {
    case 'AST_CODEC_UNAVAILABLE':
      return '本地 Agent 未安装 AST 编解码支持（AST_CODEC_UNAVAILABLE）。'
    case 'VOLCENGINE_CONNECT_FAILED':
      return '翻译服务连接失败，请检查网络或 API 配置。'
    case 'VOLCENGINE_SESSION_FAILED':
      return '翻译服务会话失败，请重试。'
    default:
      return fallback.length > 0 ? fallback : '翻译服务发生未知错误。'
  }
}

function describeError(error: unknown): string {
  return error instanceof Error ? error.message : '发生未知错误。'
}
