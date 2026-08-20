import type { Ear } from '../translation/TranslationPort'

const TTS_SAMPLE_RATE = 16_000
const PLAYBACK_LEAD_SECONDS = 0.03

export type PlaybackTarget = Ear | 'both'

export interface AudioBufferPort {
  getChannelData(channel: number): Float32Array
}

export interface AudioBufferSourcePort {
  onended: ((event: Event) => void) | null
  buffer: AudioBufferPort | null
  connect(destination: AudioNode): void
  start(when?: number): void
  stop(): void
}

export interface AudioContextPort {
  readonly state: AudioContextState
  readonly currentTime: number
  readonly destination: AudioNode
  resume(): Promise<void>
  close(): Promise<void>
  createBuffer(numberOfChannels: number, length: number, sampleRate: number): AudioBufferPort
  createBufferSource(): AudioBufferSourcePort
}

export interface AudioContextFactory {
  create(): AudioContextPort
}

interface SinkSelectableAudioContext extends AudioContextPort {
  setSinkId(deviceId: string): Promise<void>
}

export class StereoAudioPlayerError extends Error {
  public constructor(message: string) {
    super(message)
    this.name = 'StereoAudioPlayerError'
  }
}

export interface StereoTone {
  readonly left: Float32Array
  readonly right: Float32Array
  readonly sampleRate: number
}

export function pcm16LittleEndianToFloat32(pcm: ArrayBuffer): Float32Array {
  if (pcm.byteLength === 0) {
    throw new StereoAudioPlayerError('TTS PCM 音频包不能为空。')
  }
  if (pcm.byteLength % 2 !== 0) {
    throw new StereoAudioPlayerError('TTS PCM16 音频包必须包含偶数字节。')
  }

  const view = new DataView(pcm)
  const samples = new Float32Array(pcm.byteLength / 2)
  for (let index = 0; index < samples.length; index += 1) {
    const sample = view.getInt16(index * 2, true)
    samples[index] = sample < 0 ? sample / 32_768 : sample / 32_767
  }
  return samples
}

export function createEarTestTone(
  ear: Ear,
  sampleRate = 48_000,
  durationSeconds = 0.25,
  frequencyHz = 660,
): StereoTone {
  const sampleCount = Math.round(sampleRate * durationSeconds)
  const activeChannel = new Float32Array(sampleCount)
  for (let index = 0; index < sampleCount; index += 1) {
    const fade = Math.min(index / 240, (sampleCount - 1 - index) / 240, 1)
    activeChannel[index] = Math.sin((2 * Math.PI * frequencyHz * index) / sampleRate) * 0.22 * fade
  }

  const silentChannel = new Float32Array(sampleCount)
  return ear === 'left'
    ? { left: activeChannel, right: silentChannel, sampleRate }
    : { left: silentChannel, right: activeChannel, sampleRate }
}

export interface StereoAudioPlayerPort {
  readonly supportsOutputSelection: boolean
  readonly isIdle: boolean
  selectOutput(deviceId: string): Promise<void>
  playEarTest(ear: Ear): Promise<void>
  play(pcm: ArrayBuffer, target: PlaybackTarget): Promise<void>
  whenIdle(): Promise<void>
  clear(): void
  stop(): void
  reset(): void
  dispose(): void
}

export class StereoAudioPlayer implements StereoAudioPlayerPort {
  private context: AudioContextPort | null = null
  private closing: Promise<void> | null = null
  private readonly activeSources = new Set<AudioBufferSourcePort>()
  private readonly pendingStarts = new Set<object>()
  private readonly idleWaiters = new Set<() => void>()
  private nextPlaybackTime = 0
  private playbackGeneration = 0
  private disposed = false

  public constructor(private readonly contextFactory: AudioContextFactory | null) {}

  public get supportsOutputSelection(): boolean {
    return this.context !== null
      ? isSinkSelectable(this.context)
      : browserAudioContextSupportsSinkSelection()
  }

  public get isIdle(): boolean {
    return this.activeSources.size === 0 && this.pendingStarts.size === 0
  }

  public async selectOutput(deviceId: string): Promise<void> {
    const context = await this.getOrCreateContext()
    if (!isSinkSelectable(context)) {
      throw new StereoAudioPlayerError(
        '此浏览器无法直接选择音频输出。请在 macOS 系统设置中将耳机设为默认音频输出。',
      )
    }

    try {
      await context.setSinkId(deviceId)
    } catch (error: unknown) {
      throw new StereoAudioPlayerError(`无法切换到所选输出设备：${describeError(error)}`)
    }
  }

  public async playEarTest(ear: Ear): Promise<void> {
    const generation = this.playbackGeneration
    const context = await this.getRunningContext(generation)
    if (context === null) {
      return
    }
    try {
      const tone = createEarTestTone(ear)
      const buffer = context.createBuffer(2, tone.left.length, tone.sampleRate)
      buffer.getChannelData(0).set(tone.left)
      buffer.getChannelData(1).set(tone.right)
      this.startSource(context, buffer)
    } catch (error: unknown) {
      throw new StereoAudioPlayerError(`无法播放${ear === 'left' ? '左耳' : '右耳'}测试音：${describeError(error)}`)
    }
  }

  public async play(pcm: ArrayBuffer, target: PlaybackTarget): Promise<void> {
    const samples = pcm16LittleEndianToFloat32(pcm)
    const generation = this.playbackGeneration
    const pendingStart = {}
    this.pendingStarts.add(pendingStart)
    try {
      const context = await this.getRunningContext(generation)
      if (context === null) {
        return
      }

      const buffer = context.createBuffer(2, samples.length, TTS_SAMPLE_RATE)
      if (target === 'left' || target === 'both') {
        buffer.getChannelData(0).set(samples)
      }
      if (target === 'right' || target === 'both') {
        buffer.getChannelData(1).set(samples)
      }

      const startTime = Math.max(context.currentTime + PLAYBACK_LEAD_SECONDS, this.nextPlaybackTime)
      this.nextPlaybackTime = startTime + samples.length / TTS_SAMPLE_RATE
      this.startSource(context, buffer, startTime)
    } catch (error: unknown) {
      throw new StereoAudioPlayerError(`无法播放 TTS 音频：${describeError(error)}`)
    } finally {
      this.pendingStarts.delete(pendingStart)
      if (this.isIdle) {
        this.resolveIdleWaiters()
      }
    }
  }

  public whenIdle(): Promise<void> {
    if (this.isIdle) {
      return Promise.resolve()
    }
    return new Promise<void>((resolve) => this.idleWaiters.add(resolve))
  }

  public clear(): void {
    this.playbackGeneration += 1
    this.activeSources.forEach((source) => {
      source.onended = null
      try {
        source.stop()
      } catch {
        // A source may already have stopped; it is safe to ignore during cleanup.
      }
    })
    this.activeSources.clear()
    this.pendingStarts.clear()
    this.nextPlaybackTime = 0
    this.resolveIdleWaiters()
  }

  public stop(): void {
    this.clear()
  }

  public reset(): void {
    this.clear()

    const context = this.context
    this.context = null
    if (context !== null) {
      const closing = context.close().catch(() => undefined)
      this.closing = closing
      void closing.finally(() => {
        if (this.closing === closing) {
          this.closing = null
        }
      })
    }
  }

  public dispose(): void {
    if (this.disposed) {
      return
    }
    this.disposed = true
    this.reset()
  }

  private async getRunningContext(generation: number): Promise<AudioContextPort | null> {
    try {
      const context = await this.getOrCreateContext()
      if (generation !== this.playbackGeneration) {
        return null
      }
      if (context.state !== 'running') {
        await context.resume()
      }
      return generation === this.playbackGeneration ? context : null
    } catch (error: unknown) {
      throw new StereoAudioPlayerError(`无法准备音频输出：${describeError(error)}`)
    }
  }

  private startSource(context: AudioContextPort, buffer: AudioBufferPort, when?: number): void {
    const source = context.createBufferSource()
    source.buffer = buffer
    source.connect(context.destination)
    this.activeSources.add(source)
    source.onended = () => {
      this.activeSources.delete(source)
      if (this.activeSources.size === 0) {
        this.nextPlaybackTime = 0
        this.resolveIdleWaiters()
      }
    }
    try {
      source.start(when)
    } catch (error: unknown) {
      this.activeSources.delete(source)
      if (this.activeSources.size === 0) {
        this.nextPlaybackTime = 0
        this.resolveIdleWaiters()
      }
      throw error
    }
  }

  private resolveIdleWaiters(): void {
    const waiters = [...this.idleWaiters]
    this.idleWaiters.clear()
    waiters.forEach((resolve) => resolve())
  }

  private async getOrCreateContext(): Promise<AudioContextPort> {
    if (this.disposed) {
      throw new StereoAudioPlayerError('音频播放器已释放。')
    }
    if (this.closing !== null) {
      await this.closing
    }
    if (this.context === null) {
      if (this.contextFactory === null) {
        throw new StereoAudioPlayerError('此浏览器不支持 Web Audio，无法播放音频。')
      }
      this.context = this.contextFactory.create()
    }
    return this.context
  }
}

export function createBrowserAudioContextFactory(): AudioContextFactory | null {
  if (typeof AudioContext === 'undefined') {
    return null
  }
  return {
    create: () => new AudioContext({ latencyHint: 'interactive' }),
  }
}

function isSinkSelectable(context: AudioContextPort): context is SinkSelectableAudioContext {
  const candidate = context as AudioContextPort & { readonly setSinkId?: unknown }
  return typeof candidate.setSinkId === 'function'
}

function browserAudioContextSupportsSinkSelection(): boolean {
  if (typeof AudioContext === 'undefined') {
    return false
  }
  const prototype = AudioContext.prototype as AudioContext & { readonly setSinkId?: unknown }
  return typeof prototype.setSinkId === 'function'
}

function describeError(error: unknown): string {
  return error instanceof Error ? error.message : '发生未知错误。'
}
