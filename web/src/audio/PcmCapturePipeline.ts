export const AST_SAMPLE_RATE = 16_000
export const PCM_PACKET_SAMPLES = 1_280
export const PCM_PACKET_BYTES = PCM_PACKET_SAMPLES * 2
export const PCM_PACKET_DURATION_MS = 80
export const PRE_READY_MAX_PACKETS = 38

export function clampAudioSample(sample: number): number {
  return Math.max(-1, Math.min(1, sample))
}

export function downmixToMono(channels: readonly Float32Array[]): Float32Array {
  if (channels.length === 0) {
    return new Float32Array()
  }

  const sampleCount = Math.min(...channels.map((channel) => channel.length))
  const mono = new Float32Array(sampleCount)
  for (let sampleIndex = 0; sampleIndex < sampleCount; sampleIndex += 1) {
    let sum = 0
    for (const channel of channels) {
      sum += channel[sampleIndex]
    }
    mono[sampleIndex] = sum / channels.length
  }
  return mono
}

export function float32ToPcm16Samples(samples: Float32Array): Int16Array {
  const pcm = new Int16Array(samples.length)
  samples.forEach((sample, index) => {
    const clamped = clampAudioSample(sample)
    const pcmSample = clamped < 0 ? clamped * 32_768 : clamped * 32_767
    pcm[index] = Math.trunc(pcmSample)
  })
  return pcm
}

export function float32ToPcm16LittleEndian(samples: Float32Array): ArrayBuffer {
  const pcm = float32ToPcm16Samples(samples)
  const buffer = new ArrayBuffer(pcm.length * 2)
  const view = new DataView(buffer)
  pcm.forEach((sample, index) => view.setInt16(index * 2, sample, true))
  return buffer
}

export function calculateAudioLevel(samples: Float32Array): number {
  if (samples.length === 0) {
    return 0
  }
  let sumOfSquares = 0
  samples.forEach((sample) => {
    const clamped = clampAudioSample(sample)
    sumOfSquares += clamped * clamped
  })
  return Math.sqrt(sumOfSquares / samples.length)
}

/**
 * Stateful linear resampler. It retains the interpolation boundary between
 * calls, so splitting the same source into worklet quanta does not change the
 * output stream or discard fractional sample positions.
 */
export class ContinuousLinearResampler {
  private readonly sourceSamplesPerOutputSample: number
  private bufferedInput = new Float32Array()
  private bufferedInputStartPosition = 0
  private nextOutputPosition = 0

  public constructor(
    public readonly inputSampleRate: number,
    public readonly outputSampleRate: number = AST_SAMPLE_RATE,
  ) {
    if (!Number.isFinite(inputSampleRate) || inputSampleRate <= 0
      || !Number.isFinite(outputSampleRate) || outputSampleRate <= 0) {
      throw new RangeError('采样率必须是大于 0 的有限数值。')
    }
    this.sourceSamplesPerOutputSample = inputSampleRate / outputSampleRate
  }

  public process(input: Float32Array): Float32Array {
    if (input.length > 0) {
      const combined = new Float32Array(this.bufferedInput.length + input.length)
      combined.set(this.bufferedInput)
      combined.set(input, this.bufferedInput.length)
      this.bufferedInput = combined
    }

    const output: number[] = []
    while (this.canInterpolateAt(this.nextOutputPosition)) {
      const absoluteLowerIndex = Math.floor(this.nextOutputPosition)
      const lowerIndex = absoluteLowerIndex - this.bufferedInputStartPosition
      const fraction = this.nextOutputPosition - absoluteLowerIndex
      const lower = this.bufferedInput[lowerIndex]
      const upper = fraction === 0 ? lower : this.bufferedInput[lowerIndex + 1]
      output.push(lower + (upper - lower) * fraction)
      this.nextOutputPosition += this.sourceSamplesPerOutputSample
    }

    const consumedSamples = Math.min(
      Math.floor(this.nextOutputPosition) - this.bufferedInputStartPosition,
      this.bufferedInput.length,
    )
    if (consumedSamples > 0) {
      this.bufferedInput = this.bufferedInput.slice(consumedSamples)
      this.bufferedInputStartPosition += consumedSamples
    }
    return Float32Array.from(output)
  }

  public reset(): void {
    this.bufferedInput = new Float32Array()
    this.bufferedInputStartPosition = 0
    this.nextOutputPosition = 0
  }

  private canInterpolateAt(position: number): boolean {
    const absoluteLowerIndex = Math.floor(position)
    const lowerIndex = absoluteLowerIndex - this.bufferedInputStartPosition
    if (lowerIndex >= this.bufferedInput.length) {
      return false
    }
    const fraction = position - absoluteLowerIndex
    return fraction === 0 || lowerIndex + 1 < this.bufferedInput.length
  }
}

export class FixedPcmPacketizer {
  private readonly pending = new Int16Array(PCM_PACKET_SAMPLES)
  private pendingLength = 0

  public push(samples: Int16Array): readonly ArrayBuffer[] {
    const packets: ArrayBuffer[] = []
    let sourceOffset = 0
    while (sourceOffset < samples.length) {
      const copyLength = Math.min(
        PCM_PACKET_SAMPLES - this.pendingLength,
        samples.length - sourceOffset,
      )
      this.pending.set(samples.subarray(sourceOffset, sourceOffset + copyLength), this.pendingLength)
      this.pendingLength += copyLength
      sourceOffset += copyLength

      if (this.pendingLength === PCM_PACKET_SAMPLES) {
        const packet = new ArrayBuffer(PCM_PACKET_BYTES)
        const view = new DataView(packet)
        this.pending.forEach((sample, index) => view.setInt16(index * 2, sample, true))
        packets.push(packet)
        this.pendingLength = 0
      }
    }
    return packets
  }

  public get bufferedSampleCount(): number {
    return this.pendingLength
  }

  public reset(): void {
    this.pendingLength = 0
  }
}

export interface PcmPacket {
  readonly data: ArrayBuffer
  readonly audioLevel: number
  readonly capturedAtMs: number
}

/** Future TranslationClient boundary; Mock mode observes packets without uploading. */
export interface PcmPacketSink {
  push(packet: PcmPacket): void
}

export type BoundedQueueResult = 'accepted' | 'overflow'

/** Bounded pre-ready storage for a future real TranslationPort adapter. */
export class BoundedPcmPacketQueue implements PcmPacketSink {
  private readonly packets: PcmPacket[] = []

  public constructor(public readonly capacity = PRE_READY_MAX_PACKETS) {
    if (!Number.isInteger(capacity) || capacity <= 0) {
      throw new RangeError('PCM 队列容量必须是正整数。')
    }
  }

  public push(packet: PcmPacket): void {
    if (this.enqueue(packet) === 'overflow') {
      throw new Error('翻译服务启动过慢，请重试。')
    }
  }

  public enqueue(packet: PcmPacket): BoundedQueueResult {
    if (this.packets.length >= this.capacity) {
      return 'overflow'
    }
    this.packets.push(packet)
    return 'accepted'
  }

  public drain(consumer: PcmPacketSink): void {
    this.packets.splice(0).forEach((packet) => consumer.push(packet))
  }

  public clear(): void {
    this.packets.length = 0
  }

  public get size(): number {
    return this.packets.length
  }
}
