import { describe, expect, it, vi } from 'vitest'
import {
  createEarTestTone,
  StereoAudioPlayer,
  type AudioBufferPort,
  type AudioBufferSourcePort,
  type AudioContextFactory,
  type AudioContextPort,
} from './StereoAudioPlayer'

function createContext(): {
  readonly context: AudioContextPort
  readonly resume: ReturnType<typeof vi.fn>
  readonly close: ReturnType<typeof vi.fn>
  readonly createBuffer: ReturnType<typeof vi.fn>
  readonly source: AudioBufferSourcePort
} {
  const channels = [new Float32Array(), new Float32Array()]
  const buffer: AudioBufferPort = { getChannelData: (channel) => channels[channel] }
  const source: AudioBufferSourcePort = {
    onended: null,
    buffer: null,
    connect: vi.fn(),
    start: vi.fn(),
    stop: vi.fn(),
  }
  const resume = vi.fn(async () => undefined)
  const close = vi.fn(async () => undefined)
  const createBuffer = vi.fn((numberOfChannels: number, length: number) => {
    expect(numberOfChannels).toBe(2)
    channels[0] = new Float32Array(length)
    channels[1] = new Float32Array(length)
    return buffer
  })
  return {
    context: {
      state: 'suspended',
      destination: {} as AudioNode,
      resume,
      close,
      createBuffer,
      createBufferSource: () => source,
    },
    resume,
    close,
    createBuffer,
    source,
  }
}

describe('StereoAudioPlayer', () => {
  it('creates a stereo left-ear tone with an entirely silent right channel', () => {
    const tone = createEarTestTone('left', 100, 0.1, 10)

    expect(tone.left.some((sample) => sample !== 0)).toBe(true)
    expect([...tone.right]).toEqual(new Array(tone.right.length).fill(0))
  })

  it('creates a stereo right-ear tone with an entirely silent left channel', () => {
    const tone = createEarTestTone('right', 100, 0.1, 10)

    expect([...tone.left]).toEqual(new Array(tone.left.length).fill(0))
    expect(tone.right.some((sample) => sample !== 0)).toBe(true)
  })

  it('resumes the one injected context before a user-triggered ear test and starts a source', async () => {
    const testContext = createContext()
    const factory: AudioContextFactory = { create: vi.fn(() => testContext.context) }
    const player = new StereoAudioPlayer(factory)

    await player.playEarTest('left')

    expect(testContext.resume).toHaveBeenCalledOnce()
    expect(testContext.createBuffer).toHaveBeenCalledOnce()
    expect(testContext.source.start).toHaveBeenCalledOnce()
    expect(testContext.source.buffer).not.toBeNull()
  })

  it('does not silently fall back when output selection is unavailable', async () => {
    const testContext = createContext()
    const player = new StereoAudioPlayer({ create: () => testContext.context })

    await expect(player.selectOutput('headphones')).rejects.toThrow('无法直接选择音频输出')
  })

  it('stops active sources and closes its sole context when disposed', async () => {
    const testContext = createContext()
    const player = new StereoAudioPlayer({ create: () => testContext.context })
    await player.playEarTest('left')

    player.dispose()
    player.dispose()

    expect(testContext.source.stop).toHaveBeenCalledOnce()
    expect(testContext.close).toHaveBeenCalledOnce()
  })

  it('does not start a source after stop cancels a pending context resume', async () => {
    let resolveResume: (() => void) | undefined
    const testContext = createContext()
    testContext.context.resume = vi.fn(() => new Promise<void>((resolve) => { resolveResume = resolve }))
    const player = new StereoAudioPlayer({ create: () => testContext.context })

    const playback = player.playEarTest('left')
    player.stop()
    resolveResume?.()
    await playback

    expect(testContext.source.start).not.toHaveBeenCalled()
  })
})
