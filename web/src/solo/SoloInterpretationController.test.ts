import { describe, expect, it } from 'vitest'
import type { PcmPacket } from '../audio/PcmCapturePipeline'
import type {
  TranslationPort,
  TranslationRequest,
  TranslationResult,
  TranslationSession,
  TranslationSessionEvent,
} from '../translation/TranslationPort'
import { SoloInterpretationController } from './SoloInterpretationController'

class MockSession implements TranslationSession {
  private readonly listeners = new Set<(event: TranslationSessionEvent) => void>()
  private resolveDone: ((result: TranslationResult) => void) | null = null
  private rejectDone: ((reason: Error) => void) | null = null
  public readonly packets: PcmPacket[] = []
  public readonly finishInputs: string[] = []
  public cancelCalls = 0
  public readonly done = new Promise<TranslationResult>((resolve, reject) => {
    this.resolveDone = resolve
    this.rejectDone = reject
  })

  public subscribe(listener: (event: TranslationSessionEvent) => void): () => void {
    this.listeners.add(listener)
    return () => this.listeners.delete(listener)
  }

  public pushAudio(packet: PcmPacket): void {
    this.packets.push(packet)
  }

  public finish(fallbackSourceText: string): void {
    this.finishInputs.push(fallbackSourceText)
  }

  public cancel(): void {
    this.cancelCalls += 1
  }

  public emit(event: TranslationSessionEvent): void {
    this.listeners.forEach((listener) => listener(event))
  }

  public complete(result: TranslationResult): void {
    this.emit({ type: 'source_final', text: result.sourceText })
    this.emit({ type: 'translation_final', text: result.translatedText })
    this.emit({ type: 'finished' })
    this.resolveDone?.(result)
    this.resolveDone = null
    this.rejectDone = null
  }

  public fail(message: string): void {
    this.emit({ type: 'error', code: 'TEST_FAILURE', message })
    this.rejectDone?.(new Error(message))
    this.resolveDone = null
    this.rejectDone = null
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

const packet = (capturedAtMs: number): PcmPacket => ({
  data: new ArrayBuffer(16),
  audioLevel: 0.25,
  capturedAtMs,
})

describe('SoloInterpretationController', () => {
  it('runs one session per turn while earlier turns continue in parallel', async () => {
    const port = new MockPort()
    const controller = new SoloInterpretationController(port, { target: 'left' })

    const first = controller.startTurn()
    expect(first).toBe(1)
    controller.pushAudio(first as number, packet(1))
    controller.finishTurn(first as number, '你好')

    const second = controller.startTurn()
    expect(second).toBe(2)
    controller.pushAudio(second as number, packet(2))

    expect(port.sessions).toHaveLength(2)
    expect(port.sessions[0].finishInputs).toEqual(['你好'])
    expect(port.sessions[1].packets).toHaveLength(1)
    expect(controller.getSnapshot()).toMatchObject({ state: 'capturing', activeTurnId: second })

    port.sessions[0].complete({ sourceText: '你好', translatedText: 'Hello' })
    await Promise.resolve()
    expect(controller.getSnapshot()).toMatchObject({ state: 'capturing', activeTurnId: second })
  })

  it('aggregates late subtitles by turn id without overwriting the current turn', () => {
    const port = new MockPort()
    const controller = new SoloInterpretationController(port)

    const first = controller.startTurn() as number
    controller.finishTurn(first, '第一轮')
    const second = controller.startTurn() as number
    port.sessions[1].emit({ type: 'source_partial', text: '第二轮' })
    port.sessions[0].emit({ type: 'translation_final', text: 'Late first translation' })

    expect(controller.getSnapshot().turns).toEqual([
      expect.objectContaining({ id: first, translatedText: 'Late first translation' }),
      expect.objectContaining({ id: second, sourceText: '第二轮', translatedText: '' }),
    ])
  })

  it('does not let an old turn error break the current capturing turn', () => {
    const port = new MockPort()
    const controller = new SoloInterpretationController(port)

    const first = controller.startTurn() as number
    controller.finishTurn(first, '旧输入')
    const second = controller.startTurn() as number
    port.sessions[0].fail('旧 Turn 失败')

    expect(controller.getSnapshot()).toMatchObject({
      state: 'capturing',
      activeTurnId: second,
      error: null,
    })
    expect(controller.getSnapshot().turns[0]).toMatchObject({ status: 'error', error: '旧 Turn 失败' })
    controller.pushAudio(second, packet(3))
    expect(port.sessions[1].packets).toHaveLength(1)
  })

  it('cancels all unfinished sessions, removes unfinished turns, and ignores late events', () => {
    const port = new MockPort()
    const controller = new SoloInterpretationController(port)

    const first = controller.startTurn() as number
    port.sessions[0].emit({ type: 'source_partial', text: '部分一' })
    controller.finishTurn(first, '部分一')
    controller.startTurn()
    port.sessions[1].emit({ type: 'source_partial', text: '部分二' })

    controller.cancelAll()

    expect(port.sessions.map((session) => session.cancelCalls)).toEqual([1, 1])
    expect(controller.getSnapshot()).toMatchObject({ state: 'idle', activeTurnId: null, turns: [], error: null })
    port.sessions[0].emit({ type: 'translation_final', text: '过期内容' })
    expect(controller.getSnapshot().turns).toEqual([])
  })

  it('swaps languages only while idle or paused and keeps each turn route immutable', () => {
    const port = new MockPort()
    const controller = new SoloInterpretationController(port)

    expect(controller.swapLanguages()).toBe(true)
    expect(controller.getSnapshot()).toMatchObject({ sourceLanguage: 'en', targetLanguage: 'zh' })
    const turn = controller.startTurn() as number
    expect(controller.swapLanguages()).toBe(false)
    expect(controller.pause()).toBe(true)
    expect(controller.swapLanguages()).toBe(true)
    expect(controller.getSnapshot()).toMatchObject({ sourceLanguage: 'zh', targetLanguage: 'en' })
    expect(controller.getSnapshot().turns[0]).toMatchObject({ sourceLanguage: 'en', targetLanguage: 'zh' })
    expect(controller.resume()).toBe(true)
    controller.finishTurn(turn, 'Hello')
  })

  it('clears displayed transcript without allowing late events to recreate it', () => {
    const port = new MockPort()
    const controller = new SoloInterpretationController(port)
    const turn = controller.startTurn() as number
    port.sessions[0].emit({ type: 'source_final', text: '你好' })

    controller.clearTranscript()
    port.sessions[0].emit({ type: 'translation_final', text: 'Hello' })

    expect(controller.getSnapshot().turns).toEqual([])
    expect(controller.getSnapshot().activeTurnId).toBe(turn)
  })

  it('passes captions through so the client consumes PCM without playback', () => {
    const port = new MockPort()
    const controller = new SoloInterpretationController(port, { target: 'captions' })

    controller.startTurn()

    expect(port.requests[0]).toMatchObject({ sourceLanguage: 'zh', targetLanguage: 'en', targetEar: 'captions' })
    expect(controller.getSnapshot().turns[0].target).toBe('captions')
  })

  it('finishes active input and waits for all background turns', async () => {
    const port = new MockPort()
    const controller = new SoloInterpretationController(port)
    const first = controller.startTurn() as number
    controller.finishTurn(first, '一')
    controller.startTurn()

    const finishing = controller.finishAll('二')
    expect(port.sessions.map((session) => session.finishInputs)).toEqual([['一'], ['二']])
    expect(controller.getSnapshot().state).toBe('stopping')

    port.sessions[1].complete({ sourceText: '二', translatedText: 'two' })
    port.sessions[0].complete({ sourceText: '一', translatedText: 'one' })
    await finishing
    expect(controller.getSnapshot()).toMatchObject({ state: 'idle', activeTurnId: null })
  })
})
