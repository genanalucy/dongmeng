import { buildMicrophoneConstraints, type MediaStreamTrackPort } from './AudioDeviceService'
import pcmCaptureWorkletUrl from './pcm-capture.worklet.ts?worker&url'
import {
  AST_SAMPLE_RATE,
  PCM_PACKET_BYTES,
  PCM_PACKET_DURATION_MS,
  type PcmPacket,
  type PcmPacketSink,
} from './PcmCapturePipeline'

const WORKLET_PROCESSOR_NAME = 'pcm-capture-processor'

export type MicrophoneCaptureState = 'idle' | 'starting' | 'capturing' | 'error' | 'disposed'

export interface MicrophoneSnapshot {
  readonly state: MicrophoneCaptureState
  readonly inputSampleRate: number | null
  readonly astSampleRate: number
  readonly packetDurationMs: number
  readonly latestPacketBytes: number
  readonly packetCount: number
  readonly audioLevel: number
  readonly errorMessage: string | null
}

export interface MicrophoneServicePort {
  getSnapshot(): MicrophoneSnapshot
  subscribe(listener: (snapshot: MicrophoneSnapshot) => void): () => void
  start(selectedInputDeviceId: string): Promise<'started' | 'cancelled'>
  stop(): void
  dispose(): void
}

export interface MicrophoneStreamPort {
  getTracks(): readonly MediaStreamTrackPort[]
}

export interface CaptureAudioNodePort {
  connect(destination: CaptureAudioNodePort): void
  disconnect(): void
}

export interface CaptureWorkletNodePort extends CaptureAudioNodePort {
  readonly port: MessagePort
}

export interface CaptureAudioContextPort {
  readonly sampleRate: number
  readonly state: AudioContextState
  readonly destination: CaptureAudioNodePort
  resume(): Promise<void>
  close(): Promise<void>
  addWorkletModule(moduleUrl: string | URL): Promise<void>
}

export interface MicrophoneEnvironment {
  requestMicrophone(constraints: MediaStreamConstraints): Promise<MicrophoneStreamPort>
  createContext(): CaptureAudioContextPort
  createSource(context: CaptureAudioContextPort, stream: MicrophoneStreamPort): CaptureAudioNodePort
  createWorklet(context: CaptureAudioContextPort): CaptureWorkletNodePort
  createMutedSink(context: CaptureAudioContextPort): CaptureAudioNodePort
  now(): number
}

interface WorkletPacketMessage {
  readonly type: 'packet'
  readonly packet: ArrayBuffer
  readonly audioLevel: number
}

interface ActiveCapture {
  readonly stream: MicrophoneStreamPort
  readonly source: CaptureAudioNodePort
  readonly worklet: CaptureWorkletNodePort
  readonly mutedSink: CaptureAudioNodePort
}

const discardPacketSink: PcmPacketSink = { push: () => undefined }

/** Routes capture packets to the current independent translation session. */
export class MutablePcmPacketSink implements PcmPacketSink {
  private sink: PcmPacketSink = discardPacketSink

  public setSink(sink: PcmPacketSink | null): void {
    this.sink = sink ?? discardPacketSink
  }

  public push(packet: PcmPacket): void {
    this.sink.push(packet)
  }
}

export class MicrophoneServiceError extends Error {
  public constructor(message: string) {
    super(message)
    this.name = 'MicrophoneServiceError'
  }
}

/**
 * Capture owns one input-only AudioContext for the page lifetime. Playback owns
 * its separate sink-selectable context, so stopping capture cannot disrupt ear
 * routing and changing output devices cannot recreate the microphone graph.
 */
export class MicrophoneService implements MicrophoneServicePort {
  private readonly listeners = new Set<(snapshot: MicrophoneSnapshot) => void>()
  private context: CaptureAudioContextPort | null = null
  private workletModuleLoaded = false
  private workletModuleLoading: Promise<void> | null = null
  private activeCapture: ActiveCapture | null = null
  private generation = 0
  private disposed = false
  private snapshot: MicrophoneSnapshot = {
    state: 'idle',
    inputSampleRate: null,
    astSampleRate: AST_SAMPLE_RATE,
    packetDurationMs: PCM_PACKET_DURATION_MS,
    latestPacketBytes: 0,
    packetCount: 0,
    audioLevel: 0,
    errorMessage: null,
  }

  public constructor(
    private readonly environment: MicrophoneEnvironment | null,
    private readonly packetSink: PcmPacketSink = discardPacketSink,
    private readonly workletModuleUrl: string | URL = pcmCaptureWorkletUrl,
  ) {}

  public getSnapshot(): MicrophoneSnapshot {
    return { ...this.snapshot }
  }

  public subscribe(listener: (snapshot: MicrophoneSnapshot) => void): () => void {
    this.listeners.add(listener)
    listener(this.getSnapshot())
    return () => this.listeners.delete(listener)
  }

  public async start(selectedInputDeviceId: string): Promise<'started' | 'cancelled'> {
    if (this.disposed) {
      throw new MicrophoneServiceError('麦克风采集服务已释放。')
    }
    if (this.environment === null) {
      throw this.fail('此浏览器不支持 AudioWorklet 麦克风采集。')
    }
    if (selectedInputDeviceId.length === 0) {
      throw this.fail('请先选择输入麦克风。')
    }

    this.stop()
    const generation = this.generation
    this.update({
      state: 'starting',
      inputSampleRate: null,
      latestPacketBytes: 0,
      packetCount: 0,
      audioLevel: 0,
      errorMessage: null,
    })

    const context = this.getOrCreateContext()
    const streamResultPromise = this.settle(
      this.environment.requestMicrophone(buildMicrophoneConstraints(selectedInputDeviceId)),
    )
    const contextResultPromise = this.settle(this.prepareContext(context))
    const [streamResult, contextResult] = await Promise.all([streamResultPromise, contextResultPromise])

    if (this.isStale(generation)) {
      if (streamResult.status === 'fulfilled') {
        stopTracks(streamResult.value)
      }
      return 'cancelled'
    }
    if (streamResult.status === 'rejected') {
      throw this.fail(`无法开始麦克风采集：${describeError(streamResult.reason)}`)
    }
    if (contextResult.status === 'rejected') {
      stopTracks(streamResult.value)
      throw this.fail(`无法启动 AudioWorklet：${describeError(contextResult.reason)}`)
    }

    try {
      const source = this.environment.createSource(context, streamResult.value)
      const worklet = this.environment.createWorklet(context)
      const mutedSink = this.environment.createMutedSink(context)
      this.activeCapture = { stream: streamResult.value, source, worklet, mutedSink }
      worklet.port.onmessage = (event: MessageEvent<unknown>) => {
        this.handleWorkletMessage(generation, event.data)
      }
      source.connect(worklet)
      worklet.connect(mutedSink)
      mutedSink.connect(context.destination)
      this.update({ state: 'capturing', inputSampleRate: context.sampleRate })
      return 'started'
    } catch (error: unknown) {
      if (this.activeCapture === null) {
        stopTracks(streamResult.value)
      } else {
        this.disconnectCaptureGraph()
      }
      throw this.fail(`无法建立麦克风采集链路：${describeError(error)}`)
    }
  }

  public stop(): void {
    this.generation += 1
    this.releaseActiveCapture()
    if (!this.disposed && this.snapshot.state !== 'error') {
      this.update({ state: 'idle', audioLevel: 0 })
    }
  }

  public dispose(): void {
    if (this.disposed) {
      return
    }
    this.stop()
    this.disposed = true
    const context = this.context
    this.context = null
    this.workletModuleLoaded = false
    this.workletModuleLoading = null
    if (context !== null) {
      void context.close().catch(() => undefined)
    }
    this.update({ state: 'disposed', audioLevel: 0 })
    this.listeners.clear()
  }

  private getOrCreateContext(): CaptureAudioContextPort {
    if (this.context === null) {
      this.context = this.environment?.createContext() ?? null
    }
    if (this.context === null) {
      throw this.fail('此浏览器不支持 Web Audio 麦克风采集。')
    }
    return this.context
  }

  private async prepareContext(context: CaptureAudioContextPort): Promise<void> {
    if (context.state !== 'running') {
      await context.resume()
    }
    if (this.workletModuleLoaded) {
      return
    }
    if (this.workletModuleLoading === null) {
      const loading = context.addWorkletModule(this.workletModuleUrl)
      this.workletModuleLoading = loading
      void loading.then(
        () => { this.workletModuleLoaded = true },
        () => { this.workletModuleLoading = null },
      )
    }
    await this.workletModuleLoading
  }

  private handleWorkletMessage(generation: number, data: unknown): void {
    if (this.isStale(generation) || !isWorkletPacketMessage(data)) {
      return
    }
    if (data.packet.byteLength !== PCM_PACKET_BYTES) {
      this.stopWithError(`麦克风产生了异常 PCM 包：应为 ${PCM_PACKET_BYTES} bytes，实际为 ${data.packet.byteLength} bytes。`)
      return
    }

    try {
      const packet: PcmPacket = {
        data: data.packet,
        audioLevel: normalizeAudioLevel(data.audioLevel),
        capturedAtMs: this.environment?.now() ?? 0,
      }
      this.packetSink.push(packet)
      this.update({
        latestPacketBytes: data.packet.byteLength,
        packetCount: this.snapshot.packetCount + 1,
        audioLevel: packet.audioLevel,
      })
    } catch (error: unknown) {
      this.stopWithError(describeError(error))
    }
  }

  private stopWithError(message: string): void {
    this.generation += 1
    this.releaseActiveCapture()
    this.update({ state: 'error', audioLevel: 0, errorMessage: message })
  }

  private releaseActiveCapture(): void {
    const capture = this.activeCapture
    this.activeCapture = null
    if (capture === null) {
      return
    }
    capture.worklet.port.onmessage = null
    stopTracks(capture.stream)
    disconnectNode(capture.source)
    disconnectNode(capture.worklet)
    disconnectNode(capture.mutedSink)
  }

  private disconnectCaptureGraph(): void {
    this.releaseActiveCapture()
  }

  private isStale(generation: number): boolean {
    return this.disposed || generation !== this.generation
  }

  private fail(message: string): MicrophoneServiceError {
    this.update({ state: 'error', audioLevel: 0, errorMessage: message })
    return new MicrophoneServiceError(message)
  }

  private update(update: Partial<MicrophoneSnapshot>): void {
    this.snapshot = { ...this.snapshot, ...update }
    const snapshot = this.getSnapshot()
    this.listeners.forEach((listener) => listener(snapshot))
  }

  private async settle<T>(promise: Promise<T>): Promise<PromiseSettledResult<T>> {
    try {
      return { status: 'fulfilled', value: await promise }
    } catch (reason: unknown) {
      return { status: 'rejected', reason }
    }
  }
}

export function createBrowserMicrophoneEnvironment(): MicrophoneEnvironment | null {
  if (typeof AudioContext === 'undefined'
    || navigator.mediaDevices === undefined
    || typeof AudioWorkletNode === 'undefined') {
    return null
  }

  return {
    requestMicrophone: async (constraints) => navigator.mediaDevices.getUserMedia(constraints),
    createContext: () => new BrowserCaptureAudioContext(new AudioContext({ latencyHint: 'interactive' })),
    createSource: (context, stream) => requireBrowserContext(context).createSource(stream),
    createWorklet: (context) => requireBrowserContext(context).createWorklet(),
    createMutedSink: (context) => requireBrowserContext(context).createMutedSink(),
    now: () => performance.now(),
  }
}

class BrowserCaptureAudioNode implements CaptureAudioNodePort {
  public constructor(protected readonly node: AudioNode) {}

  public connect(destination: CaptureAudioNodePort): void {
    this.node.connect(requireBrowserNode(destination).node)
  }

  public disconnect(): void {
    this.node.disconnect()
  }
}

class BrowserCaptureWorkletNode extends BrowserCaptureAudioNode implements CaptureWorkletNodePort {
  public constructor(private readonly workletNode: AudioWorkletNode) {
    super(workletNode)
  }

  public get port(): MessagePort {
    return this.workletNode.port
  }
}

class BrowserCaptureAudioContext implements CaptureAudioContextPort {
  public readonly destination: CaptureAudioNodePort

  public constructor(private readonly context: AudioContext) {
    this.destination = new BrowserCaptureAudioNode(context.destination)
  }

  public get sampleRate(): number {
    return this.context.sampleRate
  }

  public get state(): AudioContextState {
    return this.context.state
  }

  public resume(): Promise<void> {
    return this.context.resume()
  }

  public close(): Promise<void> {
    return this.context.close()
  }

  public addWorkletModule(moduleUrl: string | URL): Promise<void> {
    return this.context.audioWorklet.addModule(moduleUrl)
  }

  public createSource(stream: MicrophoneStreamPort): CaptureAudioNodePort {
    if (!(stream instanceof MediaStream)) {
      throw new TypeError('浏览器麦克风流类型无效。')
    }
    return new BrowserCaptureAudioNode(this.context.createMediaStreamSource(stream))
  }

  public createWorklet(): CaptureWorkletNodePort {
    return new BrowserCaptureWorkletNode(new AudioWorkletNode(this.context, WORKLET_PROCESSOR_NAME, {
      numberOfInputs: 1,
      numberOfOutputs: 1,
      outputChannelCount: [1],
    }))
  }

  public createMutedSink(): CaptureAudioNodePort {
    const gain = this.context.createGain()
    gain.gain.value = 0
    return new BrowserCaptureAudioNode(gain)
  }
}

function requireBrowserContext(context: CaptureAudioContextPort): BrowserCaptureAudioContext {
  if (!(context instanceof BrowserCaptureAudioContext)) {
    throw new TypeError('浏览器采集 Context 类型无效。')
  }
  return context
}

function requireBrowserNode(node: CaptureAudioNodePort): BrowserCaptureAudioNode {
  if (!(node instanceof BrowserCaptureAudioNode)) {
    throw new TypeError('浏览器音频节点类型无效。')
  }
  return node
}

function isWorkletPacketMessage(data: unknown): data is WorkletPacketMessage {
  if (typeof data !== 'object' || data === null) {
    return false
  }
  const candidate = data as { readonly type?: unknown; readonly packet?: unknown; readonly audioLevel?: unknown }
  return candidate.type === 'packet'
    && candidate.packet instanceof ArrayBuffer
    && typeof candidate.audioLevel === 'number'
}

function normalizeAudioLevel(level: number): number {
  return Number.isFinite(level) ? Math.max(0, Math.min(1, level)) : 0
}

function stopTracks(stream: MicrophoneStreamPort): void {
  stream.getTracks().forEach((track) => track.stop())
}

function disconnectNode(node: CaptureAudioNodePort): void {
  try {
    node.disconnect()
  } catch {
    // Disconnect is idempotent at this service boundary.
  }
}

function describeError(error: unknown): string {
  if (error instanceof DOMException && error.name === 'NotAllowedError') {
    return '麦克风权限被拒绝。请在浏览器地址栏或 macOS 隐私设置中允许麦克风。'
  }
  return error instanceof Error ? error.message : '发生未知错误。'
}
