import { describe, expect, it } from 'vitest'
import type { PcmPacket } from '../audio/PcmCapturePipeline'
import {
  LocalAgentTranslationClient,
  type TtsPcmSink,
  type WebSocketPort,
} from './LocalAgentTranslationClient'
import type { TranslationSessionEvent } from './TranslationPort'

class FakeWebSocket implements WebSocketPort {
  public readonly CONNECTING = 0
  public readonly OPEN = 1
  public readonly CLOSING = 2
  public readonly CLOSED = 3
  public readyState = this.CONNECTING
  public bufferedAmount = 0
  public binaryType: BinaryType = 'blob'
  public onopen: ((event: Event) => void) | null = null
  public onmessage: ((event: MessageEvent<unknown>) => void) | null = null
  public onerror: ((event: Event) => void) | null = null
  public onclose: ((event: CloseEvent) => void) | null = null
  public readonly sent: (string | ArrayBuffer)[] = []
  public readonly closeCalls: { code: number | undefined; reason: string | undefined }[] = []

  public send(data: string | ArrayBuffer): void {
    this.sent.push(data)
  }

  public close(code?: number, reason?: string): void {
    this.readyState = this.CLOSED
    this.closeCalls.push({ code, reason })
  }

  public open(): void {
    this.readyState = this.OPEN
    this.onopen?.(new Event('open'))
  }

  public receiveJson(value: object): void {
    this.onmessage?.(new MessageEvent('message', { data: JSON.stringify(value) }))
  }

  public receiveRaw(data: unknown): void {
    this.onmessage?.(new MessageEvent('message', { data }))
  }

  public failConnection(): void {
    this.onerror?.(new Event('error'))
  }

  public disconnect(): void {
    this.readyState = this.CLOSED
    this.onclose?.(new Event('close') as CloseEvent)
  }
}

class RecordingTtsSink implements TtsPcmSink {
  public readonly packets: { pcm: ArrayBuffer; targetEar: 'left' | 'right' }[] = []
  public clearCalls = 0
  public isIdle = true

  public async play(pcm: ArrayBuffer, targetEar: 'left' | 'right'): Promise<void> {
    this.packets.push({ pcm, targetEar })
  }

  public clear(): void {
    this.clearCalls += 1
    this.isIdle = true
  }

  public async whenIdle(): Promise<void> {
    // Recording completes immediately.
  }
}

function packet(id: number): PcmPacket {
  const data = new ArrayBuffer(2)
  new DataView(data).setUint8(0, id)
  return { data, audioLevel: 0, capturedAtMs: id }
}

function sentKinds(socket: FakeWebSocket): string[] {
  return socket.sent.map((message) => {
    if (message instanceof ArrayBuffer) {
      return `audio:${new DataView(message).getUint8(0)}`
    }
    return (JSON.parse(message) as { type: string }).type
  })
}

function createClient(socket: FakeWebSocket, sink: TtsPcmSink = new RecordingTtsSink()): LocalAgentTranslationClient {
  return new LocalAgentTranslationClient({
    createWebSocket: () => socket,
    createSessionId: () => '123e4567-e89b-12d3-a456-426614174000',
    ttsSink: sink,
  })
}

describe('LocalAgentTranslationClient', () => {
  it('sends the Go server start contract as JSON when the socket opens', () => {
    const socket = new FakeWebSocket()
    const client = createClient(socket)
    client.start({ sourceLanguage: 'zh', targetLanguage: 'en', targetEar: 'right' })

    socket.open()

    expect(socket.binaryType).toBe('arraybuffer')
    expect(JSON.parse(socket.sent[0] as string)).toEqual({
      type: 'start', sessionId: '123e4567-e89b-12d3-a456-426614174000', mode: 's2s',
      sourceLanguage: 'zh', targetLanguage: 'en', targetAudioFormat: 'pcm', targetAudioRate: 16000,
    })
  })

  it('accepts 38 packets before ready and rejects the 39th packet', async () => {
    const socket = new FakeWebSocket()
    const session = createClient(socket).start({ sourceLanguage: 'zh', targetLanguage: 'en', targetEar: 'right' })

    for (let id = 0; id < 38; id += 1) {
      session.pushAudio(packet(id))
    }
    session.pushAudio(packet(38))

    await expect(session.done).rejects.toMatchObject({ code: 'AGENT_OFFLINE' })
    expect(socket.closeCalls).toEqual([{ code: 1011, reason: 'AGENT_OFFLINE' }])
  })

  it('flushes packets buffered before ready in capture order', () => {
    const socket = new FakeWebSocket()
    const session = createClient(socket).start({ sourceLanguage: 'zh', targetLanguage: 'en', targetEar: 'right' })
    session.pushAudio(packet(1))
    session.pushAudio(packet(2))
    socket.open()

    socket.receiveJson({ type: 'ready' })

    expect(sentKinds(socket)).toEqual(['start', 'audio:1', 'audio:2'])
  })

  it('flushes buffered packets before sending a finish requested before ready', () => {
    const socket = new FakeWebSocket()
    const session = createClient(socket).start({ sourceLanguage: 'zh', targetLanguage: 'en', targetEar: 'right' })
    session.pushAudio(packet(1))
    session.finish('unused fallback')
    socket.open()

    socket.receiveJson({ type: 'ready' })

    expect(sentKinds(socket)).toEqual(['start', 'audio:1', 'finish'])
  })

  it('fails closed under WebSocket backpressure', async () => {
    const socket = new FakeWebSocket()
    const session = createClient(socket).start({ sourceLanguage: 'zh', targetLanguage: 'en', targetEar: 'right' })
    socket.open()
    socket.receiveJson({ type: 'ready' })
    socket.bufferedAmount = 1_048_577

    session.pushAudio(packet(1))

    await expect(session.done).rejects.toMatchObject({ code: 'LOCAL_WS_BACKPRESSURE' })
    expect(socket.closeCalls).toEqual([{ code: 1011, reason: 'LOCAL_WS_BACKPRESSURE' }])
  })

  it('maps all streaming events, PCM output, and terminal result', async () => {
    const socket = new FakeWebSocket()
    const sink = new RecordingTtsSink()
    const session = createClient(socket, sink).start({ sourceLanguage: 'zh', targetLanguage: 'en', targetEar: 'right' })
    const events: TranslationSessionEvent[] = []
    session.subscribe((event) => events.push(event))
    socket.open()

    socket.receiveJson({ type: 'ready' })
    socket.receiveJson({ type: 'source_partial', message: '你' })
    socket.receiveJson({ type: 'source_final', message: '你好' })
    socket.receiveJson({ type: 'translation_partial', message: 'Hel' })
    socket.receiveJson({ type: 'translation_final', message: 'Hello' })
    const pcm = new ArrayBuffer(4)
    socket.receiveRaw(pcm)
    socket.receiveJson({ type: 'finished' })

    await expect(session.done).resolves.toEqual({ sourceText: '你好', translatedText: 'Hello' })
    expect(events).toEqual([
      { type: 'ready' }, { type: 'source_partial', text: '你' }, { type: 'source_final', text: '你好' },
      { type: 'translation_partial', text: 'Hel' }, { type: 'translation_final', text: 'Hello' },
      { type: 'tts_audio', pcm }, { type: 'finished' },
    ])
    expect(sink.packets).toEqual([{ pcm, targetEar: 'right' }])
    expect(socket.closeCalls).toEqual([{ code: 1000, reason: 'finished' }])
  })

  it('aggregates incremental final subtitle segments and previews partial text', async () => {
    const socket = new FakeWebSocket()
    const session = createClient(socket).start({ sourceLanguage: 'en', targetLanguage: 'zh', targetEar: 'left' })
    const events: TranslationSessionEvent[] = []
    session.subscribe((event) => events.push(event))
    socket.open()
    socket.receiveJson({ type: 'ready' })

    socket.receiveJson({ type: 'source_final', message: 'Hello.' })
    socket.receiveJson({ type: 'translation_final', message: '你好。' })
    socket.receiveJson({ type: 'source_partial', message: 'How are' })
    socket.receiveJson({ type: 'translation_partial', message: '你最近' })
    socket.receiveJson({ type: 'source_final', message: 'How are you?' })
    socket.receiveJson({ type: 'translation_final', message: '你最近怎么样？' })
    socket.receiveJson({ type: 'finished' })

    await expect(session.done).resolves.toEqual({
      sourceText: 'Hello. How are you?',
      translatedText: '你好。你最近怎么样？',
    })
    expect(events).toContainEqual({ type: 'source_partial', text: 'Hello. How are' })
    expect(events).toContainEqual({ type: 'translation_partial', text: '你好。你最近' })
    expect(events).toContainEqual({ type: 'source_final', text: 'Hello. How are you?' })
    expect(events).toContainEqual({ type: 'translation_final', text: '你好。你最近怎么样？' })
  })

  it('does not duplicate cumulative final subtitle payloads', async () => {
    const socket = new FakeWebSocket()
    const session = createClient(socket).start({ sourceLanguage: 'en', targetLanguage: 'zh', targetEar: 'left' })
    socket.open()
    socket.receiveJson({ type: 'ready' })
    socket.receiveJson({ type: 'source_final', message: 'Hello.' })
    socket.receiveJson({ type: 'source_final', message: 'Hello. How are you?' })
    socket.receiveJson({ type: 'translation_final', message: '你好。' })
    socket.receiveJson({ type: 'translation_final', message: '你好。你怎么样？' })
    socket.receiveJson({ type: 'finished' })

    await expect(session.done).resolves.toEqual({
      sourceText: 'Hello. How are you?',
      translatedText: '你好。你怎么样？',
    })
  })

  it('consumes captions-only PCM without playing it', async () => {
    const socket = new FakeWebSocket()
    const sink = new RecordingTtsSink()
    const session = createClient(socket, sink).start({ sourceLanguage: 'zh', targetLanguage: 'en', targetEar: 'captions' })
    const events: TranslationSessionEvent[] = []
    session.subscribe((event) => events.push(event))
    socket.open()
    socket.receiveJson({ type: 'ready' })
    socket.receiveRaw(new ArrayBuffer(2))
    socket.receiveJson({ type: 'finished' })

    await expect(session.done).resolves.toEqual({ sourceText: '', translatedText: '' })
    expect(sink.packets).toEqual([])
    expect(events.some((event) => event.type === 'tts_audio')).toBe(false)
  })

  it('accepts zero TTS and finishes immediately', async () => {
    const socket = new FakeWebSocket()
    const session = createClient(socket).start({ sourceLanguage: 'zh', targetLanguage: 'en', targetEar: 'right' })
    socket.open()
    socket.receiveJson({ type: 'ready' })
    socket.receiveJson({ type: 'finished' })

    await expect(session.done).resolves.toEqual({ sourceText: '', translatedText: '' })
    expect(socket.closeCalls).toEqual([{ code: 1000, reason: 'finished' }])
  })

  it('accepts multiple PCM chunks without exposing upstream sentence markers', async () => {
    const socket = new FakeWebSocket()
    const sink = new RecordingTtsSink()
    const session = createClient(socket, sink).start({ sourceLanguage: 'zh', targetLanguage: 'en', targetEar: 'right' })
    socket.open()
    socket.receiveJson({ type: 'ready' })

    const firstPcm = new ArrayBuffer(2)
    const secondPcm = new ArrayBuffer(4)
    socket.receiveRaw(firstPcm)
    socket.receiveRaw(secondPcm)
    socket.receiveJson({ type: 'finished' })

    await expect(session.done).resolves.toEqual({ sourceText: '', translatedText: '' })
    expect(sink.packets).toEqual([
      { pcm: firstPcm, targetEar: 'right' },
      { pcm: secondPcm, targetEar: 'right' },
    ])
  })

  it('rejects binary PCM before ready', async () => {
    const socket = new FakeWebSocket()
    const sink = new RecordingTtsSink()
    const session = createClient(socket, sink).start({ sourceLanguage: 'zh', targetLanguage: 'en', targetEar: 'right' })
    socket.open()
    socket.receiveRaw(new ArrayBuffer(2))

    await expect(session.done).rejects.toMatchObject({ code: 'TRANSLATION_PROTOCOL_ERROR' })
    expect(sink.clearCalls).toBe(1)
  })

  it.each([
    ['upstream TTS marker', (socket: FakeWebSocket) => socket.receiveJson({ type: 'tts_start' })],
    ['empty PCM', (socket: FakeWebSocket) => socket.receiveRaw(new ArrayBuffer(0))],
    ['odd-byte PCM', (socket: FakeWebSocket) => socket.receiveRaw(new ArrayBuffer(3))],
  ])('rejects invalid simplified stream: %s', async (_name, trigger) => {
    const socket = new FakeWebSocket()
    const sink = new RecordingTtsSink()
    const session = createClient(socket, sink).start({ sourceLanguage: 'zh', targetLanguage: 'en', targetEar: 'right' })
    socket.open()
    socket.receiveJson({ type: 'ready' })

    trigger(socket)

    await expect(session.done).rejects.toMatchObject({ code: 'TRANSLATION_PROTOCOL_ERROR' })
    expect(sink.clearCalls).toBe(1)
  })

  it('waits for all PCM scheduling promises after finished', async () => {
    const socket = new FakeWebSocket()
    let resolvePlayback: () => void = () => { throw new Error('playback did not start') }
    const playbackPromise = new Promise<void>((resolve) => { resolvePlayback = resolve })
    const sink: TtsPcmSink = {
      play: () => playbackPromise,
      clear: () => undefined,
      isIdle: false,
      whenIdle: async () => undefined,
    }
    const session = createClient(socket, sink).start({ sourceLanguage: 'zh', targetLanguage: 'en', targetEar: 'right' })
    socket.open()
    socket.receiveJson({ type: 'ready' })
    socket.receiveRaw(new ArrayBuffer(2))
    socket.receiveJson({ type: 'finished' })

    expect(socket.closeCalls).toEqual([])
    resolvePlayback()
    await expect(session.done).resolves.toEqual({ sourceText: '', translatedText: '' })
    expect(socket.closeCalls).toEqual([{ code: 1000, reason: 'finished' }])
  })

  it('keeps usable subtitles and queued TTS when the upstream session later fails', async () => {
    const socket = new FakeWebSocket()
    const sink = new RecordingTtsSink()
    const session = createClient(socket, sink).start({ sourceLanguage: 'zh', targetLanguage: 'en', targetEar: 'right' })
    socket.open()
    socket.receiveJson({ type: 'ready' })
    socket.receiveJson({ type: 'source_final', message: '你好' })
    socket.receiveJson({ type: 'translation_final', message: 'Hello' })
    const pcm = new ArrayBuffer(4)
    socket.receiveRaw(pcm)
    socket.receiveJson({ type: 'error', code: 'VOLCENGINE_SESSION_FAILED' })
    socket.disconnect()

    await expect(session.done).resolves.toEqual({ sourceText: '你好', translatedText: 'Hello' })
    expect(sink.packets).toEqual([{ pcm, targetEar: 'right' }])
    expect(sink.clearCalls).toBe(0)
  })

  it('preserves usable output across the native onerror then onclose sequence', async () => {
    const socket = new FakeWebSocket()
    const sink = new RecordingTtsSink()
    const session = createClient(socket, sink).start({ sourceLanguage: 'zh', targetLanguage: 'en', targetEar: 'right' })
    socket.open()
    socket.receiveJson({ type: 'ready' })
    socket.receiveJson({ type: 'translation_final', message: 'Hello' })

    socket.failConnection()
    socket.disconnect()

    await expect(session.done).resolves.toEqual({ sourceText: '', translatedText: 'Hello' })
    expect(sink.clearCalls).toBe(0)
  })

  it('reports a playback rejection after partial completion without clearing other queued audio', async () => {
    const socket = new FakeWebSocket()
    let rejectPlayback: (reason: Error) => void = () => undefined
    let markPlaybackStarted: () => void = () => undefined
    const playbackStarted = new Promise<void>((resolve) => { markPlaybackStarted = resolve })
    let clearCalls = 0
    const sink: TtsPcmSink = {
      play: () => new Promise<void>((_resolve, reject) => {
        rejectPlayback = reject
        markPlaybackStarted()
      }),
      clear: () => { clearCalls += 1 },
      isIdle: false,
      whenIdle: async () => undefined,
    }
    const session = createClient(socket, sink).start({ sourceLanguage: 'zh', targetLanguage: 'en', targetEar: 'right' })
    socket.open()
    socket.receiveJson({ type: 'ready' })
    socket.receiveRaw(new ArrayBuffer(2))
    socket.receiveJson({ type: 'error', code: 'VOLCENGINE_SESSION_FAILED' })

    await playbackStarted
    rejectPlayback(new Error('output disconnected'))

    await expect(session.done).rejects.toMatchObject({ code: 'TTS_PLAYBACK_FAILED' })
    expect(clearCalls).toBe(0)
  })

  it('still fails when the upstream session produces no usable output', async () => {
    const socket = new FakeWebSocket()
    const sink = new RecordingTtsSink()
    const session = createClient(socket, sink).start({ sourceLanguage: 'zh', targetLanguage: 'en', targetEar: 'right' })
    socket.open()
    socket.receiveJson({ type: 'ready' })
    socket.receiveJson({ type: 'error', code: 'VOLCENGINE_SESSION_FAILED' })

    await expect(session.done).rejects.toMatchObject({ code: 'VOLCENGINE_SESSION_FAILED' })
    expect(sink.clearCalls).toBe(1)
  })

  it('turns a TTS sink failure into a terminal session error', async () => {
    const socket = new FakeWebSocket()
    const sink: TtsPcmSink = {
      play: async () => { throw new Error('output disconnected') },
      clear: () => undefined,
      isIdle: true,
      whenIdle: async () => undefined,
    }
    const session = createClient(socket, sink).start({ sourceLanguage: 'zh', targetLanguage: 'en', targetEar: 'left' })
    socket.open()
    socket.receiveJson({ type: 'ready' })
    socket.receiveRaw(new ArrayBuffer(2))

    await expect(session.done).rejects.toMatchObject({ code: 'TTS_PLAYBACK_FAILED' })
  })

  it('accepts Go errors without message or logId and maps their user message', async () => {
    const socket = new FakeWebSocket()
    const session = createClient(socket).start({ sourceLanguage: 'zh', targetLanguage: 'en', targetEar: 'right' })
    const events: TranslationSessionEvent[] = []
    session.subscribe((event) => events.push(event))

    socket.receiveJson({ type: 'error', code: 'AST_CODEC_UNAVAILABLE' })

    await expect(session.done).rejects.toMatchObject({
      code: 'AST_CODEC_UNAVAILABLE',
      message: '本地 Agent 未安装 AST 编解码支持（AST_CODEC_UNAVAILABLE）。',
    })
    expect(events).toContainEqual({
      type: 'error', code: 'AST_CODEC_UNAVAILABLE', message: '本地 Agent 未安装 AST 编解码支持（AST_CODEC_UNAVAILABLE）。',
    })
  })

  it.each([
    ['invalid JSON', (socket: FakeWebSocket) => socket.receiveRaw('{')],
    ['unknown fields', (socket: FakeWebSocket) => socket.receiveJson({ type: 'ready', extra: true })],
    ['disconnect', (socket: FakeWebSocket) => socket.disconnect()],
  ])('fails for %s', async (_name, trigger) => {
    const socket = new FakeWebSocket()
    const session = createClient(socket).start({ sourceLanguage: 'zh', targetLanguage: 'en', targetEar: 'right' })

    trigger(socket)

    await expect(session.done).rejects.toMatchObject({ code: expect.any(String) })
  })

  it('does not close after finish until the server sends finished', () => {
    const socket = new FakeWebSocket()
    const session = createClient(socket).start({ sourceLanguage: 'zh', targetLanguage: 'en', targetEar: 'right' })
    socket.open()
    socket.receiveJson({ type: 'ready' })

    session.finish('fallback')

    expect(sentKinds(socket)).toEqual(['start', 'finish'])
    expect(socket.closeCalls).toEqual([])
  })

  it('plays concurrent session TTS in turn creation order', async () => {
    const firstSocket = new FakeWebSocket()
    const secondSocket = new FakeWebSocket()
    const sockets = [firstSocket, secondSocket]
    let socketIndex = 0
    const played: number[] = []
    const sink: TtsPcmSink = {
      play: async (pcm) => { played.push(new Uint8Array(pcm)[0]) },
      clear: () => undefined,
      isIdle: true,
      whenIdle: async () => undefined,
    }
    const client = new LocalAgentTranslationClient({
      createWebSocket: () => sockets[socketIndex++],
      createSessionId: () => crypto.randomUUID(),
      ttsSink: sink,
    })
    const first = client.start({ sourceLanguage: 'zh', targetLanguage: 'en', targetEar: 'right' })
    const second = client.start({ sourceLanguage: 'en', targetLanguage: 'zh', targetEar: 'left' })
    firstSocket.open()
    secondSocket.open()
    firstSocket.receiveJson({ type: 'ready' })
    secondSocket.receiveJson({ type: 'ready' })

    secondSocket.receiveRaw(new Uint8Array([2, 0]).buffer)
    await Promise.resolve()
    expect(played).toEqual([])

    firstSocket.receiveRaw(new Uint8Array([1, 0]).buffer)
    firstSocket.receiveJson({ type: 'finished' })
    secondSocket.receiveJson({ type: 'finished' })

    await expect(first.done).resolves.toBeDefined()
    await expect(second.done).resolves.toBeDefined()
    expect(played).toEqual([1, 2])
  })

  it('creates an independent WebSocket session for every start', () => {
    const sockets = [new FakeWebSocket(), new FakeWebSocket()]
    let nextSocket = 0
    const ids = ['first', 'second']
    let nextId = 0
    const client = new LocalAgentTranslationClient({
      createWebSocket: () => sockets[nextSocket++],
      createSessionId: () => ids[nextId++],
    })

    client.start({ sourceLanguage: 'zh', targetLanguage: 'en', targetEar: 'right' })
    client.start({ sourceLanguage: 'en', targetLanguage: 'zh', targetEar: 'left' })
    sockets[0].open()
    sockets[1].open()

    expect(sockets[0]).not.toBe(sockets[1])
    expect(JSON.parse(sockets[0].sent[0] as string)).toMatchObject({ sessionId: 'first', sourceLanguage: 'zh' })
    expect(JSON.parse(sockets[1].sent[0] as string)).toMatchObject({ sessionId: 'second', sourceLanguage: 'en' })
  })
})
