import {
  AST_SAMPLE_RATE,
  calculateAudioLevel,
  ContinuousLinearResampler,
  downmixToMono,
  FixedPcmPacketizer,
  float32ToPcm16Samples,
} from './PcmCapturePipeline'

const PROCESSOR_NAME = 'pcm-capture-processor'

declare const sampleRate: number

declare abstract class AudioWorkletProcessor {
  public readonly port: MessagePort
  public abstract process(
    inputs: readonly (readonly Float32Array[])[],
    outputs: readonly (readonly Float32Array[])[],
    parameters: Readonly<Record<string, Float32Array>>,
  ): boolean
}

declare function registerProcessor(
  name: string,
  processor: new (options?: AudioWorkletNodeOptions) => AudioWorkletProcessor,
): void

interface WorkletPacketMessage {
  readonly type: 'packet'
  readonly packet: ArrayBuffer
  readonly audioLevel: number
}

class PcmCaptureProcessor extends AudioWorkletProcessor {
  private readonly resampler = new ContinuousLinearResampler(sampleRate, AST_SAMPLE_RATE)
  private readonly packetizer = new FixedPcmPacketizer()

  public process(inputs: readonly (readonly Float32Array[])[]): boolean {
    const channels = inputs[0]
    if (channels === undefined || channels.length === 0) {
      return true
    }

    const mono = downmixToMono(channels)
    const audioLevel = calculateAudioLevel(mono)
    const resampled = this.resampler.process(mono)
    const packets = this.packetizer.push(float32ToPcm16Samples(resampled))
    packets.forEach((packet) => {
      const message: WorkletPacketMessage = { type: 'packet', packet, audioLevel }
      this.port.postMessage(message, [packet])
    })
    return true
  }
}

registerProcessor(PROCESSOR_NAME, PcmCaptureProcessor)
