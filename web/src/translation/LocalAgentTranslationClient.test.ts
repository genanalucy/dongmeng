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
