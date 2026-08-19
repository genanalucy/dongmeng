import { describe, expect, it, vi } from 'vitest'
import {
  BoundedPcmPacketQueue,
  ContinuousLinearResampler,
  downmixToMono,
  FixedPcmPacketizer,
  float32ToPcm16LittleEndian,
  PCM_PACKET_BYTES,
  PCM_PACKET_SAMPLES,
  type PcmPacketSink,
} from './PcmCapturePipeline'

describe('PCM capture transforms', () => {
  it('downmixes every Float32 channel to mono without mutating the inputs', () => {
    const left = new Float32Array([1, 0.5, -1])
    const right = new Float32Array([-1, 0.25, 1])

    expect([...downmixToMono([left, right])]).toEqual([0, 0.375, 0])
    expect([...left]).toEqual([1, 0.5, -1])
    expect([...right]).toEqual([-1, 0.25, 1])
  })

  it('clamps Float32 and writes signed PCM16 little-endian', () => {
    const result = float32ToPcm16LittleEndian(new Float32Array([-2, -1, -0.5, 0, 0.5, 1, 2]))
    const view = new DataView(result)

    expect([...Array(7).keys()].map((index) => view.getInt16(index * 2, true))).toEqual([
      -32_768,
      -32_768,
      -16_384,
      0,
      16_383,
      32_767,
      32_767,
    ])
    expect([...new Uint8Array(result.slice(0, 2))]).toEqual([0, 128])
  })

  it.each([44_100, 48_000, 96_000])(
    'produces the same continuous resample stream at %i Hz regardless of quantum boundaries',
    (inputRate) => {
      const inputLength = Math.round(inputRate * 0.24)
      const input = Float32Array.from(
        { length: inputLength },
        (_, index) => Math.sin((2 * Math.PI * 440 * index) / inputRate),
      )
      const whole = new ContinuousLinearResampler(inputRate).process(input)
      const streamedResampler = new ContinuousLinearResampler(inputRate)
      const chunks: number[] = []
      for (let offset = 0; offset < input.length; offset += 128) {
        chunks.push(...streamedResampler.process(input.subarray(offset, offset + 128)))
      }

      expect(chunks.length).toBe(whole.length)
      expect(chunks).toEqual([...whole])
      expect(whole.length).toBeGreaterThanOrEqual(3_839)
      expect(whole.length).toBeLessThanOrEqual(3_840)
    },
  )

  it('accumulates across arbitrary worklet-sized chunks into exact 1280-sample packets', () => {
    const packetizer = new FixedPcmPacketizer()
    const source = Int16Array.from({ length: PCM_PACKET_SAMPLES * 2 + 17 }, (_, index) => index - 1_200)
    const packets: ArrayBuffer[] = []
    for (let offset = 0; offset < source.length; offset += 127) {
      packets.push(...packetizer.push(source.subarray(offset, offset + 127)))
    }

    expect(packets).toHaveLength(2)
    expect(packets.every((packet) => packet.byteLength === PCM_PACKET_BYTES)).toBe(true)
    const reconstructed = packets.flatMap((packet) => (
      [...new Int16Array(packet)].map((sample) => sample)
    ))
    expect(reconstructed).toEqual([...source.subarray(0, PCM_PACKET_SAMPLES * 2)])
    expect(packetizer.bufferedSampleCount).toBe(17)
  })

  it('keeps the future pre-ready sink bounded and drains packets in order', () => {
    const queue = new BoundedPcmPacketQueue(2)
    const packets = [1, 2, 3].map((value) => ({
      data: Uint8Array.of(value).buffer,
      audioLevel: 0,
      capturedAtMs: value,
    }))
    expect(queue.enqueue(packets[0])).toBe('accepted')
    expect(queue.enqueue(packets[1])).toBe('accepted')
    expect(queue.enqueue(packets[2])).toBe('overflow')

    const push = vi.fn()
    const sink: PcmPacketSink = { push }
    queue.drain(sink)

    expect(push.mock.calls.map(([packet]) => packet.capturedAtMs)).toEqual([1, 2])
    expect(queue.size).toBe(0)
  })
})
