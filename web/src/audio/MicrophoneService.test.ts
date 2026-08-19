import { describe, expect, it, vi } from 'vitest'
import { buildMicrophoneConstraints, type MediaStreamTrackPort } from './AudioDeviceService'
import {
  MicrophoneService,
  type CaptureAudioContextPort,
  type CaptureAudioNodePort,
  type CaptureWorkletNodePort,
  type MicrophoneEnvironment,
  type MicrophoneStreamPort,
} from './MicrophoneService'
import { PCM_PACKET_BYTES, type PcmPacketSink } from './PcmCapturePipeline'

interface Deferred<T> {
  readonly promise: Promise<T>
  readonly resolve: (value: T) => void
  readonly reject: (reason: unknown) => void
}

function createDeferred<T>(): Deferred<T> {
  let resolvePromise: ((value: T) => void) | undefined
  let rejectPromise: ((reason: unknown) => void) | undefined
  const promise = new Promise<T>((resolve, reject) => {
    resolvePromise = resolve
    rejectPromise = reject
  })
  return {
    promise,
    resolve: (value) => resolvePromise?.(value),
    reject: (reason) => rejectPromise?.(reason),
  }
}

type MockedCaptureAudioNode = CaptureAudioNodePort & {
  readonly connect: ReturnType<typeof vi.fn<(destination: CaptureAudioNodePort) => void>>
  readonly disconnect: ReturnType<typeof vi.fn<() => void>>
}

function createNode(): MockedCaptureAudioNode {
  return {
    connect: vi.fn<(destination: CaptureAudioNodePort) => void>(),
    disconnect: vi.fn<() => void>(),
  }
}

function createHarness(requestMicrophone?: MicrophoneEnvironment['requestMicrophone']): {
  readonly environment: MicrophoneEnvironment
  readonly context: CaptureAudioContextPort
  readonly stream: MicrophoneStreamPort
  readonly trackStop: ReturnType<typeof vi.fn>
  readonly source: ReturnType<typeof createNode>
  readonly worklet: CaptureWorkletNodePort & ReturnType<typeof createNode>
  readonly mutedSink: ReturnType<typeof createNode>
  readonly port: MessagePort
  readonly close: ReturnType<typeof vi.fn>
  readonly addWorkletModule: ReturnType<typeof vi.fn>
  readonly createSource: ReturnType<typeof vi.fn>
} {
  const trackStop = vi.fn()
  const track: MediaStreamTrackPort = { stop: trackStop }
  const stream: MicrophoneStreamPort = { getTracks: () => [track] }
  const source = createNode()
  const mutedSink = createNode()
  const port = {
    onmessage: null,
    postMessage: vi.fn(),
  } as unknown as MessagePort
  const worklet = { ...createNode(), port }
  const close = vi.fn(async () => undefined)
  const addWorkletModule = vi.fn(async () => undefined)
  const context: CaptureAudioContextPort = {
    sampleRate: 48_000,
    state: 'suspended',
    destination: createNode(),
    resume: vi.fn(async () => undefined),
    close,
    addWorkletModule,
  }
  const createSource = vi.fn(() => source)
  return {
    context,
    stream,
    trackStop,
    source,
    worklet,
    mutedSink,
    port,
    close,
    addWorkletModule,
    createSource,
    environment: {
      requestMicrophone: requestMicrophone ?? vi.fn(async () => stream),
      createContext: vi.fn(() => context),
      createSource,
      createWorklet: vi.fn(() => worklet),
      createMutedSink: vi.fn(() => mutedSink),
      now: () => 42,
    },
  }
}

describe('MicrophoneService', () => {
  it('starts from the exact selected device, loads one worklet, and observes 2560-byte packets', async () => {
    const harness = createHarness()
    const push = vi.fn()
    const sink: PcmPacketSink = { push }
    const service = new MicrophoneService(harness.environment, sink, '/pcm-worklet.js')

    await expect(service.start('selected-mic')).resolves.toBe('started')
    expect(harness.environment.requestMicrophone).toHaveBeenCalledWith(
      buildMicrophoneConstraints('selected-mic'),
    )
    expect(harness.addWorkletModule).toHaveBeenCalledWith('/pcm-worklet.js')
    expect(harness.source.connect).toHaveBeenCalledWith(harness.worklet)
    expect(harness.worklet.connect).toHaveBeenCalledWith(harness.mutedSink)

    harness.port.onmessage?.({
      data: { type: 'packet', packet: new ArrayBuffer(PCM_PACKET_BYTES), audioLevel: 0.4 },
    } as MessageEvent<unknown>)

    expect(push).toHaveBeenCalledWith(expect.objectContaining({
      audioLevel: 0.4,
      capturedAtMs: 42,
    }))
    expect(service.getSnapshot()).toMatchObject({
      state: 'capturing',
      inputSampleRate: 48_000,
      latestPacketBytes: PCM_PACKET_BYTES,
      packetCount: 1,
      audioLevel: 0.4,
    })
  })

  it('does not attach a capture graph when stop wins an asynchronous start race', async () => {
    const request = createDeferred<MicrophoneStreamPort>()
    const harness = createHarness(() => request.promise)
    const service = new MicrophoneService(harness.environment, undefined, '/pcm-worklet.js')

    const starting = service.start('selected-mic')
    service.stop()
    request.resolve(harness.stream)

    await expect(starting).resolves.toBe('cancelled')
    expect(harness.trackStop).toHaveBeenCalledOnce()
    expect(harness.createSource).not.toHaveBeenCalled()
    expect(service.getSnapshot().state).toBe('idle')
  })

  it('stops tracks, disconnects every node, and closes the sole capture context on dispose', async () => {
    const harness = createHarness()
    const service = new MicrophoneService(harness.environment, undefined, '/pcm-worklet.js')
    await service.start('selected-mic')

    service.dispose()
    service.dispose()

    expect(harness.trackStop).toHaveBeenCalledOnce()
    expect(harness.source.disconnect).toHaveBeenCalledOnce()
    expect(harness.worklet.disconnect).toHaveBeenCalledOnce()
    expect(harness.mutedSink.disconnect).toHaveBeenCalledOnce()
    expect(harness.close).toHaveBeenCalledOnce()
    expect(service.getSnapshot().state).toBe('disposed')
  })

  it('releases the stream and exposes a readable error when worklet startup fails', async () => {
    const harness = createHarness()
    harness.context.addWorkletModule = vi.fn(async () => {
      throw new Error('module blocked')
    })
    const service = new MicrophoneService(harness.environment, undefined, '/pcm-worklet.js')

    await expect(service.start('selected-mic')).rejects.toThrow('无法启动 AudioWorklet：module blocked')
    expect(harness.trackStop).toHaveBeenCalledOnce()
    expect(service.getSnapshot()).toMatchObject({
      state: 'error',
      errorMessage: '无法启动 AudioWorklet：module blocked',
    })
  })
})
