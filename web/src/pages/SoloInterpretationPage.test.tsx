import { act, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, describe, expect, it, vi, type Mock } from 'vitest'
import type { AudioDeviceServicePort, AudioDeviceSnapshot } from '../audio/AudioDeviceService'
import { MutablePcmPacketSink, type MicrophoneServicePort, type MicrophoneSnapshot } from '../audio/MicrophoneService'
import type { StereoAudioPlayerPort } from '../audio/StereoAudioPlayer'
import { SoloInterpretationController } from '../solo/SoloInterpretationController'
import type {
  TranslationPort,
  TranslationRequest,
  TranslationResult,
  TranslationSession,
  TranslationSessionEvent,
} from '../translation/TranslationPort'
import { SoloInterpretationPage } from './SoloInterpretationPage'

const readyDevices: AudioDeviceSnapshot = {
  inputDevices: [{ deviceId: 'mic', groupId: '', kind: 'audioinput', label: 'Microphone' }],
  outputDevices: [{ deviceId: 'phones', groupId: '', kind: 'audiooutput', label: 'Headphones' }],
  selectedInputDeviceId: 'mic',
  selectedOutputDeviceId: 'phones',
  microphonePermissionGranted: true,
  outputDisconnected: false,
  errorMessage: null,
}

const idleMicrophone: MicrophoneSnapshot = {
  state: 'idle',
  inputSampleRate: null,
  astSampleRate: 16_000,
  packetDurationMs: 80,
  latestPacketBytes: 0,
  packetCount: 0,
  audioLevel: 0,
  errorMessage: null,
}

class TestSession implements TranslationSession {
  private readonly listeners = new Set<(event: TranslationSessionEvent) => void>()
  private resolveDone: ((result: TranslationResult) => void) | null = null
  public readonly done = new Promise<TranslationResult>((resolve) => { this.resolveDone = resolve })
  public readonly finish = vi.fn<(fallbackSourceText: string) => void>()
  public readonly cancel = vi.fn()
  public readonly pushAudio = vi.fn()

  public subscribe(listener: (event: TranslationSessionEvent) => void): () => void {
    this.listeners.add(listener)
    return () => this.listeners.delete(listener)
  }

  public complete(): void {
    const result = { sourceText: '原文', translatedText: 'translation' }
    this.resolveDone?.(result)
    this.resolveDone = null
  }
}

class TestPort implements TranslationPort {
  public readonly requests: TranslationRequest[] = []
  public readonly sessions: TestSession[] = []

  public start(request: TranslationRequest): TranslationSession {
    const session = new TestSession()
    this.requests.push(request)
    this.sessions.push(session)
    return session
  }
}

function createDeviceService(): AudioDeviceServicePort {
  return {
    getSnapshot: () => readyDevices,
    subscribe: (listener) => { listener(readyDevices); return () => undefined },
    requestPermission: async () => undefined,
    refreshDevices: async () => undefined,
    selectInput: async () => undefined,
    selectOutput: async () => undefined,
    clearOutputSelection: () => undefined,
    dispose: () => undefined,
  }
}

interface TestMicrophoneService extends MicrophoneServicePort {
  readonly start: Mock<(deviceId: string) => Promise<'started' | 'cancelled'>>
  readonly stop: Mock<() => void>
}

function createMicrophoneService(): TestMicrophoneService {
  return {
    getSnapshot: () => idleMicrophone,
    subscribe: (listener) => { listener(idleMicrophone); return () => undefined },
    start: vi.fn<(deviceId: string) => Promise<'started' | 'cancelled'>>().mockResolvedValue('started'),
    stop: vi.fn<() => void>(),
    dispose: () => undefined,
  }
}

interface TestAudioPlayer extends StereoAudioPlayerPort {
  readonly stop: Mock<() => void>
}

function createAudioPlayer(): TestAudioPlayer {
  return {
    supportsOutputSelection: true,
    isIdle: true,
    selectOutput: async () => undefined,
    playEarTest: async () => undefined,
    play: async () => undefined,
    whenIdle: async () => undefined,
    clear: () => undefined,
    stop: vi.fn<() => void>(),
    reset: () => undefined,
    dispose: () => undefined,
  }
}

const onlineHealth = { status: 'online', checkedAtMs: 1, checking: false, errorMessage: null } as const

function renderPage(port: TestPort, microphoneService = createMicrophoneService(), audioPlayer = createAudioPlayer()) {
  const controller = new SoloInterpretationController(port)
  const packetSink = new MutablePcmPacketSink()
  const result = render(
    <SoloInterpretationPage
      controller={controller}
      onBack={() => undefined}
      deviceService={createDeviceService()}
      microphoneService={microphoneService}
      audioPlayer={audioPlayer}
      packetSink={packetSink}
      agentHealth={onlineHealth}
    />,
  )
  return { ...result, controller, microphoneService, audioPlayer }
}

describe('SoloInterpretationPage', () => {
  afterEach(() => vi.useRealTimers())

  it('starts a turn and starts the selected physical microphone once', () => {
    const port = new TestPort()
    const { microphoneService, controller } = renderPage(port)

    fireEvent.click(screen.getByRole('button', { name: '开始同传' }))

    expect(microphoneService.start).toHaveBeenCalledOnce()
    expect(microphoneService.start).toHaveBeenCalledWith('mic')
    expect(port.requests).toHaveLength(1)
    expect(controller.getSnapshot()).toMatchObject({ state: 'capturing', activeTurnId: 1 })
  })

  it('rolls to a new turn after 8 seconds without restarting the microphone', () => {
    vi.useFakeTimers()
    const port = new TestPort()
    const { microphoneService, controller } = renderPage(port)
    fireEvent.click(screen.getByRole('button', { name: '开始同传' }))

    vi.advanceTimersByTime(8_000)

    expect(port.sessions[0].finish).toHaveBeenCalledOnce()
    expect(port.requests).toHaveLength(2)
    expect(microphoneService.start).toHaveBeenCalledOnce()
    expect(microphoneService.stop).not.toHaveBeenCalled()
    expect(controller.getSnapshot()).toMatchObject({ state: 'capturing', activeTurnId: 2 })
  })

  it('keeps continuous capture running when the browser window loses focus', () => {
    const port = new TestPort()
    const { microphoneService, controller } = renderPage(port)
    fireEvent.click(screen.getByRole('button', { name: '开始同传' }))

    fireEvent.blur(window)

    expect(microphoneService.stop).not.toHaveBeenCalled()
    expect(port.sessions[0].cancel).not.toHaveBeenCalled()
    expect(controller.getSnapshot()).toMatchObject({ state: 'capturing', activeTurnId: 1 })
  })

  it('finishes and stops capture on pause, then starts a fresh turn and microphone on resume', () => {
    const port = new TestPort()
    const { microphoneService, controller } = renderPage(port)
    fireEvent.click(screen.getByRole('button', { name: '开始同传' }))

    fireEvent.click(screen.getByRole('button', { name: '暂停' }))
    expect(port.sessions[0].finish).toHaveBeenCalledOnce()
    expect(microphoneService.stop).toHaveBeenCalledOnce()
    expect(controller.getSnapshot().state).toBe('paused')

    fireEvent.click(screen.getByRole('button', { name: '恢复' }))
    expect(port.requests).toHaveLength(2)
    expect(microphoneService.start).toHaveBeenCalledTimes(2)
    expect(controller.getSnapshot()).toMatchObject({ state: 'capturing', activeTurnId: 2 })
  })

  it('sends captions as the target request', () => {
    const port = new TestPort()
    renderPage(port)

    fireEvent.click(screen.getByRole('radio', { name: '仅字幕' }))
    fireEvent.click(screen.getByRole('button', { name: '开始同传' }))

    expect(port.requests[0]).toMatchObject({ targetEar: 'captions' })
  })

  it('cancels capture when the selected input microphone disappears', async () => {
    const port = new TestPort()
    const microphoneService = createMicrophoneService()
    let publishDevices: (snapshot: AudioDeviceSnapshot) => void = () => undefined
    const deviceService: AudioDeviceServicePort = {
      ...createDeviceService(),
      subscribe: (listener) => {
        publishDevices = listener
        listener(readyDevices)
        return () => undefined
      },
    }
    const controller = new SoloInterpretationController(port)
    render(
      <SoloInterpretationPage
        controller={controller}
        onBack={() => undefined}
        deviceService={deviceService}
        microphoneService={microphoneService}
        audioPlayer={createAudioPlayer()}
        packetSink={new MutablePcmPacketSink()}
        agentHealth={onlineHealth}
      />,
    )
    fireEvent.click(screen.getByRole('button', { name: '开始同传' }))

    act(() => publishDevices({ ...readyDevices, inputDevices: [], selectedInputDeviceId: null }))

    await waitFor(() => expect(microphoneService.stop).toHaveBeenCalled())
    expect(port.sessions[0].cancel).toHaveBeenCalledOnce()
    expect(controller.getSnapshot()).toMatchObject({ state: 'idle', activeTurnId: null })
  })

  it('cancels all turns, stops capture and playback on unmount', () => {
    const port = new TestPort()
    const { unmount, microphoneService, audioPlayer, controller } = renderPage(port)
    fireEvent.click(screen.getByRole('button', { name: '开始同传' }))
    microphoneService.stop.mockClear()
    audioPlayer.stop.mockClear()

    unmount()

    expect(microphoneService.stop).toHaveBeenCalledOnce()
    expect(audioPlayer.stop).toHaveBeenCalledOnce()
    expect(port.sessions[0].cancel).toHaveBeenCalledOnce()
    expect(controller.getSnapshot()).toMatchObject({ state: 'idle', activeTurnId: null })
  })
})
