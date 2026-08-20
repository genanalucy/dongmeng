import { describe, expect, it } from 'vitest'
import type { PcmPacket } from '../audio/PcmCapturePipeline'
import { FaceToFaceController, routeForSide } from './FaceToFaceController'
import type {
  TranslationPort,
  TranslationRequest,
  TranslationResult,
  TranslationSession,
  TranslationSessionEvent,
} from '../translation/TranslationPort'

class MockSession implements TranslationSession {
  private readonly listeners = new Set<(event: TranslationSessionEvent) => void>()
  private resolveDone: ((value: TranslationResult) => void) | null = null
  public readonly packets: PcmPacket[] = []
  public readonly finishTexts: string[] = []
  public cancelCalls = 0
  public readonly done = new Promise<TranslationResult>((resolve) => { this.resolveDone = resolve })

  public subscribe(listener: (event: TranslationSessionEvent) => void): () => void {
    this.listeners.add(listener)
    return () => this.listeners.delete(listener)
  }

  public pushAudio(packet: PcmPacket): void {
    this.packets.push(packet)
  }

  public finish(fallbackSourceText: string): void {
    this.finishTexts.push(fallbackSourceText)
  }

  public cancel(): void {
    this.cancelCalls += 1
  }

  public complete(result: TranslationResult): void {
    this.emit({ type: 'source_final', text: result.sourceText })
    this.emit({ type: 'translation_final', text: result.translatedText })
    this.emit({ type: 'finished' })
    this.resolveDone?.(result)
  }

  public emit(event: TranslationSessionEvent): void {
    this.listeners.forEach((listener) => listener(event))
  }
}

class DeferredPlayback {
  public isIdle = false
  public clearCalls = 0
  private resolveIdle: (() => void) | null = null

  public whenIdle(): Promise<void> {
    return new Promise<void>((resolve) => { this.resolveIdle = resolve })
  }

  public clear(): void {
    this.clearCalls += 1
    this.finish()
  }

  public finish(): void {
    this.isIdle = true
    this.resolveIdle?.()
    this.resolveIdle = null
  }
}

class MockPort implements TranslationPort {
  public readonly requests: TranslationRequest[] = []
  public readonly sessions: MockSession[] = []

  public start(request: TranslationRequest): TranslationSession {
    const session = new MockSession()
    this.requests.push(request)
    this.sessions.push(session)
    return session
  }
}

describe('FaceToFaceController', () => {
  it('maps each speaker to the opposite listening ear and keeps speaker ear silent by contract', () => {
    expect(routeForSide('left', 'zh', 'en')).toEqual({
      sourceLanguage: 'zh', targetLanguage: 'en', speakerEar: 'left', listenerEar: 'right',
    })
    expect(routeForSide('right', 'zh', 'en')).toEqual({
      sourceLanguage: 'en', targetLanguage: 'zh', speakerEar: 'right', listenerEar: 'left',
    })
  })

  it('enforces half duplex through speaking, translating, and ready', async () => {
    const port = new MockPort()
    const controller = new FaceToFaceController(port)

    expect(controller.startSpeaking('left')).toBe(true)
    expect(controller.getSnapshot().state).toBe('left_speaking')
    expect(controller.startSpeaking('right')).toBe(false)
    controller.pushAudio({ data: new ArrayBuffer(2560), audioLevel: 0.2, capturedAtMs: 1 })
    expect(port.sessions[0].packets).toHaveLength(1)

    const stopping = controller.stopSpeaking('你好，我叫李明。')
    expect(controller.getSnapshot().state).toBe('left_translating')
    expect(port.sessions[0].finishTexts).toEqual(['你好，我叫李明。'])
    expect(controller.startSpeaking('right')).toBe(false)
    port.sessions[0].complete({ sourceText: '你好，我叫李明。', translatedText: 'Hello, my name is Li Ming.' })
    await stopping

    expect(controller.getSnapshot().subtitles[0]).toMatchObject({
      sourceLanguage: 'zh', targetLanguage: 'en', listenerEar: 'right',
    })
    expect(controller.getSnapshot().state).toBe('ready')
    expect(controller.startSpeaking('right')).toBe(true)
    expect(port.requests[1]).toMatchObject({ sourceLanguage: 'en', targetLanguage: 'zh', targetEar: 'left' })
  })

  it('shows and updates streaming subtitles before the session is finished', () => {
    const port = new MockPort()
    const controller = new FaceToFaceController(port)
    controller.startSpeaking('left')

    port.sessions[0].emit({ type: 'source_partial', text: '你好' })
    expect(controller.getSnapshot().subtitles).toHaveLength(1)
    expect(controller.getSnapshot().subtitles[0]).toMatchObject({ sourceText: '你好', translatedText: '' })

    port.sessions[0].emit({ type: 'translation_partial', text: 'Hello' })
    expect(controller.getSnapshot().subtitles).toHaveLength(1)
    expect(controller.getSnapshot().subtitles[0]).toMatchObject({ sourceText: '你好', translatedText: 'Hello' })
  })

  it('keeps half duplex locked until the session is finished and playback is idle', async () => {
    const port = new MockPort()
    const playback = new DeferredPlayback()
    const controller = new FaceToFaceController(port, playback)
    controller.startSpeaking('left')

    const stopping = controller.stopSpeaking('你好')
    port.sessions[0].complete({ sourceText: '你好', translatedText: 'Hello' })
    await Promise.resolve()

    expect(controller.getSnapshot().state).toBe('left_translating')
    expect(controller.startSpeaking('right')).toBe(false)

    playback.finish()
    await stopping

    expect(controller.getSnapshot().state).toBe('ready')
    expect(controller.startSpeaking('right')).toBe(true)
  })

  it('swaps only languages while preserving physical ears', () => {
    const controller = new FaceToFaceController(new MockPort())

    expect(controller.swapLanguages()).toBe(true)
    expect(controller.getSnapshot()).toMatchObject({ leftLanguage: 'en', rightLanguage: 'zh' })
    expect(routeForSide('left', 'en', 'zh').listenerEar).toBe('right')
  })

  it('keeps the shared playback queue for a playback-preserving terminal error', () => {
    const port = new MockPort()
    const playback = new DeferredPlayback()
    const controller = new FaceToFaceController(port, playback)
    controller.startSpeaking('left')
    port.sessions[0].emit({ type: 'source_final', text: '你好' })

    port.sessions[0].emit({
      type: 'error', code: 'TTS_PLAYBACK_FAILED', message: '一个音频包播放失败', preservePlayback: true,
    })

    expect(controller.getSnapshot().state).toBe('error')
    expect(controller.getSnapshot().subtitles).toHaveLength(1)
    expect(playback.clearCalls).toBe(0)
  })

  it('preserves already displayed subtitles when the session later fails', () => {
    const port = new MockPort()
    const controller = new FaceToFaceController(port)
    controller.startSpeaking('left')
    port.sessions[0].emit({ type: 'source_final', text: '你好' })
    port.sessions[0].emit({ type: 'translation_final', text: 'Hello' })

    port.sessions[0].emit({ type: 'error', code: 'VOLCENGINE_SESSION_FAILED', message: '翻译服务会话失败，请重试。' })

    expect(controller.getSnapshot()).toMatchObject({ state: 'error', activeSide: null })
    expect(controller.getSnapshot().subtitles).toHaveLength(1)
    expect(controller.getSnapshot().subtitles[0]).toMatchObject({ sourceText: '你好', translatedText: 'Hello' })
  })

  it('returns to ready and cancels the active streaming session', () => {
    const port = new MockPort()
    const controller = new FaceToFaceController(port)
    controller.startSpeaking('right')

    controller.cancelActiveTurn()

    expect(port.sessions[0].cancelCalls).toBe(1)
    expect(controller.getSnapshot()).toMatchObject({ state: 'ready', activeSide: null })
  })

  it('ignores an old session result after its active turn is cancelled', async () => {
    const port = new MockPort()
    const controller = new FaceToFaceController(port)
    controller.startSpeaking('left')
    const stopping = controller.stopSpeaking('过期的输入')

    controller.cancelActiveTurn()
    port.sessions[0].complete({ sourceText: '过期的输入', translatedText: 'stale input' })
    await stopping

    expect(controller.getSnapshot()).toMatchObject({ state: 'ready', activeSide: null, subtitles: [] })
  })

  it('recovers from an external device error only through the explicit ready path', async () => {
    const port = new MockPort()
    const controller = new FaceToFaceController(port)
    controller.startSpeaking('right')
    const stopping = controller.stopSpeaking('stale turn')

    controller.reportExternalError('耳机已断开')
    expect(port.sessions[0].cancelCalls).toBe(1)
    expect(controller.getSnapshot()).toMatchObject({ state: 'error', activeSide: null })
    expect(controller.recoverFromExternalError()).toBe(true)
    expect(controller.getSnapshot()).toMatchObject({ state: 'ready', activeSide: null, errorMessage: null })

    port.sessions[0].complete({ sourceText: 'stale turn', translatedText: '过期译文' })
    await stopping

    expect(controller.getSnapshot().subtitles).toEqual([])
    expect(controller.startSpeaking('left')).toBe(true)
  })
})
