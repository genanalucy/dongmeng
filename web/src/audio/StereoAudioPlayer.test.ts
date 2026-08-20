import { describe, expect, it, vi } from 'vitest'
import {
  createEarTestTone,
  pcm16LittleEndianToFloat32,
  StereoAudioPlayer,
  type AudioBufferPort,
  type AudioBufferSourcePort,
  type AudioContextFactory,
  type AudioContextPort,
} from './StereoAudioPlayer'

interface RecordedBuffer extends AudioBufferPort {
  readonly channels: readonly Float32Array[]
  readonly sampleRate: number
}

interface TestContext {
  readonly context: AudioContextPort
  readonly resume: ReturnType<typeof vi.fn>
  readonly close: ReturnType<typeof vi.fn>
  readonly buffers: RecordedBuffer[]
  readonly sources: AudioBufferSourcePort[]
  readonly starts: (number | undefined)[]
  setCurrentTime(value: number): void
}

function createContext(initialState: AudioContextState = 'running'): TestContext {
  let currentTime = 0
  const buffers: RecordedBuffer[] = []
  const sources: AudioBufferSourcePort[] = []
  const starts: (number | undefined)[] = []
  const resume = vi.fn(async () => undefined)
  const close = vi.fn(async () => undefined)
  const context: AudioContextPort = {
    state: initialState,
    get currentTime() { return currentTime },
    destination: {} as AudioNode,
    resume,
    close,
    createBuffer: (numberOfChannels, length, sampleRate) => {
      const channels = Array.from({ length: numberOfChannels }, () => new Float32Array(length))
      const buffer: RecordedBuffer = {
        channels,
        sampleRate,
        getChannelData: (channel) => channels[channel],
      }
      buffers.push(buffer)
      return buffer
    },
    createBufferSource: () => {
      const source: AudioBufferSourcePort = {
        onended: null,
        buffer: null,
        connect: vi.fn(),
        start: vi.fn((when?: number) => starts.push(when)),
        stop: vi.fn(),
      }
      sources.push(source)
      return source
    },
  }
  return { context, resume, close, buffers, sources, starts, setCurrentTime: (value) => { currentTime = value } }
}

function pcm16(...samples: number[]): ArrayBuffer {
  const buffer = new ArrayBuffer(samples.length * 2)
  const view = new DataView(buffer)
  samples.forEach((sample, index) => view.setInt16(index * 2, sample, true))
  return buffer
}

function end(source: AudioBufferSourcePort): void {
  source.onended?.(new Event('ended'))
}

describe('StereoAudioPlayer', () => {
  it('decodes little-endian PCM16 with asymmetric signed normalization', () => {
    const samples = pcm16LittleEndianToFloat32(pcm16(-32_768, -16_384, 0, 16_384, 32_767))

    expect([...samples]).toEqual([-1, -0.5, 0, Math.fround(16_384 / 32_767), 1])
    const littleEndianBytes = new Uint8Array([0x01, 0x00]).buffer
    expect(pcm16LittleEndianToFloat32(littleEndianBytes)[0]).toBe(Math.fround(1 / 32_767))
  })

  it.each([
    ['left', [1, Math.fround(16_384 / 32_767)], [0, 0]],
    ['right', [0, 0], [1, Math.fround(16_384 / 32_767)]],
    ['both', [1, Math.fround(16_384 / 32_767)], [1, Math.fround(16_384 / 32_767)]],
  ] as const)('routes PCM to %s stereo channels', async (target, expectedLeft, expectedRight) => {
    const testContext = createContext()
    const player = new StereoAudioPlayer({ create: () => testContext.context })

    await player.play(pcm16(32_767, 16_384), target)

    expect(testContext.buffers[0].sampleRate).toBe(16_000)
    expect([...testContext.buffers[0].channels[0]]).toEqual(expectedLeft)
    expect([...testContext.buffers[0].channels[1]]).toEqual(expectedRight)
  })

  it('schedules streaming chunks continuously from one 30ms lead time', async () => {
    const testContext = createContext()
    testContext.setCurrentTime(5)
    const player = new StereoAudioPlayer({ create: () => testContext.context })

    await player.play(pcm16(...new Array(1_600).fill(1)), 'left')
    testContext.setCurrentTime(5.02)
    await player.play(pcm16(...new Array(800).fill(1)), 'left')

    expect(testContext.starts[0]).toBeCloseTo(5.03)
    expect(testContext.starts[1]).toBeCloseTo(5.13)
  })

  it('becomes idle only after every scheduled source ends', async () => {
    const testContext = createContext()
    const player = new StereoAudioPlayer({ create: () => testContext.context })
    await player.play(pcm16(1), 'left')
    await player.play(pcm16(2), 'left')
    let idleResolved = false
    void player.whenIdle().then(() => { idleResolved = true })

    end(testContext.sources[0])
    await Promise.resolve()
    expect(player.isIdle).toBe(false)
    expect(idleResolved).toBe(false)

    end(testContext.sources[1])
    await player.whenIdle()
    expect(player.isIdle).toBe(true)
    expect(idleResolved).toBe(true)
  })

  it('clears active sources, resolves waiters, and ignores late ended callbacks', async () => {
    const testContext = createContext()
    const player = new StereoAudioPlayer({ create: () => testContext.context })
    await player.play(pcm16(1), 'right')
    const source = testContext.sources[0]
    const idle = player.whenIdle()

    player.clear()
    source.onended?.(new Event('ended'))
    await idle

    expect(source.stop).toHaveBeenCalledOnce()
    expect(player.isIdle).toBe(true)
  })

  it('reset models output disconnection by cancelling idle waits and closing the sole context', async () => {
    const testContext = createContext()
    const factory: AudioContextFactory = { create: vi.fn(() => testContext.context) }
    const player = new StereoAudioPlayer(factory)
    await player.play(pcm16(1), 'left')
    const idle = player.whenIdle()

    player.reset()
    await idle

    expect(testContext.sources[0].stop).toHaveBeenCalledOnce()
    expect(testContext.close).toHaveBeenCalledOnce()
    expect(player.isIdle).toBe(true)
  })

  it('rejects empty and odd-byte PCM packets', async () => {
    const player = new StereoAudioPlayer({ create: () => createContext().context })

    await expect(player.play(new ArrayBuffer(0), 'left')).rejects.toThrow('不能为空')
    await expect(player.play(new ArrayBuffer(3), 'left')).rejects.toThrow('偶数字节')
  })

  it('creates isolated ear-test channels and reuses the same context', async () => {
    const tone = createEarTestTone('left', 100, 0.1, 10)
    expect(tone.left.some((sample) => sample !== 0)).toBe(true)
    expect([...tone.right]).toEqual(new Array(tone.right.length).fill(0))

    const testContext = createContext('suspended')
    const create = vi.fn(() => testContext.context)
    const player = new StereoAudioPlayer({ create })
    await player.playEarTest('left')
    await player.play(pcm16(1), 'right')

    expect(create).toHaveBeenCalledOnce()
    expect(testContext.resume).toHaveBeenCalledTimes(2)
  })

  it('does not silently fall back when output selection is unavailable', async () => {
    const testContext = createContext()
    const player = new StereoAudioPlayer({ create: () => testContext.context })

    await expect(player.selectOutput('headphones')).rejects.toThrow('无法直接选择音频输出')
  })

  it('does not start a source after clear cancels a pending context resume', async () => {
    let resolveResume: (() => void) | undefined
    const testContext = createContext('suspended')
    testContext.context.resume = vi.fn(() => new Promise<void>((resolve) => { resolveResume = resolve }))
    const player = new StereoAudioPlayer({ create: () => testContext.context })

    const playback = player.play(pcm16(1), 'left')
    player.clear()
    resolveResume?.()
    await playback

    expect(testContext.sources).toHaveLength(0)
  })
})
