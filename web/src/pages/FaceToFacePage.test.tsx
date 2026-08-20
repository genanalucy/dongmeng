import { StrictMode } from 'react'
import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { AudioDeviceService, type AudioDeviceServicePort, type AudioDeviceSnapshot } from '../audio/AudioDeviceService'
import { StereoAudioPlayer, type StereoAudioPlayerPort } from '../audio/StereoAudioPlayer'
import { MicrophoneService, type MicrophoneServicePort, type MicrophoneSnapshot } from '../audio/MicrophoneService'
import { FaceToFaceController } from '../face/FaceToFaceController'
import { DeterministicMockTranslationPort } from '../translation/TranslationPort'
import { FaceToFacePage } from './FaceToFacePage'

const readyDevices: AudioDeviceSnapshot = {
  inputDevices: [{ deviceId: 'mic', groupId: '', kind: 'audioinput', label: 'MacBook Microphone' }],
  outputDevices: [{ deviceId: 'headphones', groupId: '', kind: 'audiooutput', label: 'AirPods' }],
  selectedInputDeviceId: 'mic',
  selectedOutputDeviceId: 'headphones',
  microphonePermissionGranted: true,
  outputDisconnected: false,
  errorMessage: null,
}

function createReadyDeviceService(): AudioDeviceServicePort {
  return {
    getSnapshot: () => readyDevices,
    subscribe: (listener) => {
      listener(readyDevices)
      return () => undefined
    },
    requestPermission: async () => undefined,
    refreshDevices: async () => undefined,
    selectInput: async () => undefined,
    selectOutput: async () => undefined,
    clearOutputSelection: () => undefined,
    dispose: () => undefined,
  }
}

function createAudioPlayer(supportsOutputSelection = true): StereoAudioPlayerPort {
  return {
    supportsOutputSelection,
    isIdle: true,
    selectOutput: async () => undefined,
    playEarTest: async () => undefined,
    play: async () => undefined,
    whenIdle: async () => undefined,
    clear: () => undefined,
    stop: () => undefined,
    reset: () => undefined,
    dispose: () => undefined,
  }
}

const idleMicrophoneSnapshot: MicrophoneSnapshot = {
  state: 'idle',
  inputSampleRate: null,
  astSampleRate: 16_000,
  packetDurationMs: 80,
  latestPacketBytes: 0,
  packetCount: 0,
  audioLevel: 0,
  errorMessage: null,
}

function createMicrophoneService(): MicrophoneServicePort {
  return {
    getSnapshot: () => idleMicrophoneSnapshot,
    subscribe: (listener) => {
      listener(idleMicrophoneSnapshot)
      return () => undefined
    },
    start: async () => 'started',
    stop: () => undefined,
    dispose: () => undefined,
  }
}

function renderPage(): void {
  render(
    <FaceToFacePage
      controller={new FaceToFaceController(new DeterministicMockTranslationPort())}
      onBack={() => undefined}
      deviceService={createReadyDeviceService()}
      audioPlayer={createAudioPlayer()}
      microphoneService={createMicrophoneService()}
    />,
  )
}

describe('FaceToFacePage', () => {
  afterEach(() => vi.useRealTimers())

  it('locks the opposite PTT while a participant holds the button and shows a simulated turn', async () => {
    renderPage()
    const leftButton = screen.getByRole('button', { name: /左耳.*按住说话 中文/i })
    const rightButton = screen.getByRole('button', { name: /右耳.*hold to speak english/i })

    fireEvent.pointerDown(leftButton)
    expect(rightButton).toBeDisabled()

    fireEvent.pointerUp(leftButton)
    expect(rightButton).toBeDisabled()
    await screen.findByText(/Hello, my name is Li Ming\./)
    expect(screen.getByText(/播放目标：右耳/)).toBeInTheDocument()

    await waitFor(() => expect(rightButton).toBeEnabled(), { timeout: 1200 })
  })

  it('starts capture immediately from the selected device and stops it on pointerup', () => {
    const microphoneService = {
      ...createMicrophoneService(),
      start: vi.fn(async () => 'started' as const),
      stop: vi.fn(),
    }
    render(
      <FaceToFacePage
        controller={new FaceToFaceController(new DeterministicMockTranslationPort())}
        onBack={() => undefined}
        deviceService={createReadyDeviceService()}
        audioPlayer={createAudioPlayer()}
        microphoneService={microphoneService}
      />,
    )
    const leftButton = screen.getByRole('button', { name: /左耳.*按住说话 中文/i })

    fireEvent.pointerDown(leftButton)
    expect(microphoneService.start).toHaveBeenCalledWith('mic')
    fireEvent.pointerUp(leftButton)

    expect(microphoneService.stop).toHaveBeenCalledOnce()
  })

  it('automatically finishes a PTT turn before the upstream session timeout', () => {
    vi.useFakeTimers()
    const microphoneService = {
      ...createMicrophoneService(),
      stop: vi.fn(),
    }
    const controller = new FaceToFaceController(new DeterministicMockTranslationPort())
    render(
      <FaceToFacePage
        controller={controller}
        onBack={() => undefined}
        deviceService={createReadyDeviceService()}
        audioPlayer={createAudioPlayer()}
        microphoneService={microphoneService}
      />,
    )
    fireEvent.pointerDown(screen.getByRole('button', { name: /左耳.*按住说话 中文/i }))

    vi.advanceTimersByTime(25_000)

    expect(microphoneService.stop).toHaveBeenCalled()
    expect(controller.getSnapshot().state).not.toBe('left_speaking')
  })

  it('ignores a stale microphone start rejection after the turn has already ended', async () => {
    let rejectStart: (reason: Error) => void = () => undefined
    const microphoneService = {
      ...createMicrophoneService(),
      start: vi.fn(() => new Promise<'started'>((_resolve, reject) => { rejectStart = reject })),
    }
    const controller = new FaceToFaceController(new DeterministicMockTranslationPort())
    render(
      <FaceToFacePage
        controller={controller}
        onBack={() => undefined}
        deviceService={createReadyDeviceService()}
        audioPlayer={createAudioPlayer()}
        microphoneService={microphoneService}
      />,
    )
    const leftButton = screen.getByRole('button', { name: /左耳.*按住说话 中文/i })
    fireEvent.pointerDown(leftButton)
    fireEvent.pointerUp(leftButton)

    rejectStart(new Error('late start failure'))
    await Promise.resolve()

    expect(controller.getSnapshot().state).not.toBe('error')
  })

  it('stops microphone capture when the controller terminates a speaking turn', async () => {
    const microphoneService = {
      ...createMicrophoneService(),
      stop: vi.fn(),
    }
    const controller = new FaceToFaceController(new DeterministicMockTranslationPort())
    render(
      <FaceToFacePage
        controller={controller}
        onBack={() => undefined}
        deviceService={createReadyDeviceService()}
        audioPlayer={createAudioPlayer()}
        microphoneService={microphoneService}
      />,
    )
    fireEvent.pointerDown(screen.getByRole('button', { name: /左耳.*按住说话 中文/i }))

    controller.reportExternalError('session terminated')

    await waitFor(() => expect(microphoneService.stop).toHaveBeenCalled())
  })

  it('ends an active PTT turn and stops capture when the window loses focus', () => {
    const microphoneService = {
      ...createMicrophoneService(),
      stop: vi.fn(),
    }
    render(
      <FaceToFacePage
        controller={new FaceToFaceController(new DeterministicMockTranslationPort())}
        onBack={() => undefined}
        deviceService={createReadyDeviceService()}
        audioPlayer={createAudioPlayer()}
        microphoneService={microphoneService}
      />,
    )
    const leftButton = screen.getByRole('button', { name: /左耳.*按住说话 中文/i })
    const rightButton = screen.getByRole('button', { name: /右耳.*hold to speak english/i })

    fireEvent.pointerDown(leftButton)
    expect(rightButton).toBeDisabled()
    fireEvent(window, new Event('blur'))

    expect(microphoneService.stop).toHaveBeenCalled()
    expect(rightButton).toBeEnabled()
  })

  it('ends the turn in a readable error when capture startup fails', async () => {
    const microphoneService = {
      ...createMicrophoneService(),
      start: vi.fn(async () => { throw new Error('无法启动 AudioWorklet：module blocked') }),
      stop: vi.fn(),
    }
    render(
      <FaceToFacePage
        controller={new FaceToFaceController(new DeterministicMockTranslationPort())}
        onBack={() => undefined}
        deviceService={createReadyDeviceService()}
        audioPlayer={createAudioPlayer()}
        microphoneService={microphoneService}
      />,
    )

    fireEvent.pointerDown(screen.getByRole('button', { name: /左耳.*按住说话 中文/i }))

    expect(await screen.findByRole('alert')).toHaveTextContent('无法启动 AudioWorklet：module blocked')
    expect(microphoneService.stop).toHaveBeenCalled()
    expect(screen.getByText(/发生错误/)).toBeInTheDocument()
  })

  it('treats pointercancel like pointerup and runs the simulated translation', async () => {
    renderPage()
    const leftButton = screen.getByRole('button', { name: /左耳.*按住说话 中文/i })
    const rightButton = screen.getByRole('button', { name: /右耳.*hold to speak english/i })

    fireEvent.pointerDown(leftButton)
    expect(rightButton).toBeDisabled()

    fireEvent.pointerCancel(leftButton)

    expect(rightButton).toBeDisabled()
    await screen.findByText(/Hello, my name is Li Ming\./)
    expect(screen.getByText(/播放目标：右耳/)).toBeInTheDocument()
  })

  it('recovers from an external error after a successful ready device selection', async () => {
    let deviceListener: ((snapshot: AudioDeviceSnapshot) => void) | undefined
    const disconnectedDevices: AudioDeviceSnapshot = {
      ...readyDevices,
      selectedOutputDeviceId: null,
      outputDisconnected: true,
    }
    const deviceService: AudioDeviceServicePort = {
      ...createReadyDeviceService(),
      subscribe: (listener) => {
        deviceListener = listener
        listener(disconnectedDevices)
        return () => undefined
      },
      selectOutput: async () => {
        deviceListener?.(readyDevices)
      },
    }
    render(
      <FaceToFacePage
        controller={new FaceToFaceController(new DeterministicMockTranslationPort())}
        onBack={() => undefined}
        deviceService={deviceService}
        audioPlayer={createAudioPlayer()}
        microphoneService={createMicrophoneService()}
      />,
    )

    expect(await screen.findByRole('alert')).toHaveTextContent('耳机已断开，请重新连接。')
    await userEvent.setup().selectOptions(screen.getByLabelText('输出设备'), 'headphones')

    await waitFor(() => expect(screen.getByText('已就绪 · 可选择一侧按住说话', { exact: true })).toBeInTheDocument())
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: /左耳.*按住说话 中文/i })).toBeEnabled()
  })

  it('disables language swapping while speaking or translating', async () => {
    renderPage()
    const leftButton = screen.getByRole('button', { name: /左耳.*按住说话 中文/i })
    const swapButton = screen.getByRole('button', { name: '交换左右语言' })

    fireEvent.pointerDown(leftButton)
    expect(swapButton).toBeDisabled()

    fireEvent.pointerUp(leftButton)
    expect(swapButton).toBeDisabled()
    expect(screen.getByText('LEFT · 左耳').parentElement).toHaveTextContent('中文')
    expect(screen.getByText('RIGHT · 右耳').parentElement).toHaveTextContent('English')

    await screen.findByText(/Hello, my name is Li Ming\./)
  })

  it('swaps displayed languages only while ready and marks ear tests', async () => {
    const user = userEvent.setup()
    renderPage()

    await user.click(screen.getByRole('button', { name: '交换左右语言' }))
    expect(screen.getByText('LEFT · 左耳').parentElement).toHaveTextContent('English')
    expect(screen.getByText('RIGHT · 右耳').parentElement).toHaveTextContent('中文')

    await user.click(screen.getByRole('button', { name: '测试左耳' }))
    expect(screen.getByRole('button', { name: /左耳正常/ })).toHaveTextContent('✓ 左耳正常')
  })

  it('fails closed instead of playing an ear test through the default output', async () => {
    const user = userEvent.setup()
    const playEarTest = vi.fn(async () => undefined)
    render(
      <FaceToFacePage
        controller={new FaceToFaceController(new DeterministicMockTranslationPort())}
        onBack={() => undefined}
        deviceService={createReadyDeviceService()}
        audioPlayer={{
          ...createAudioPlayer(false),
          playEarTest,
        }}
        microphoneService={createMicrophoneService()}
      />,
    )

    expect(screen.getByRole('button', { name: '测试左耳' })).toBeDisabled()
    await user.selectOptions(screen.getByLabelText('输出设备'), 'headphones')

    expect(playEarTest).not.toHaveBeenCalled()
    expect(await screen.findByRole('alert')).toHaveTextContent('不能播放到默认扬声器')
  })

  it('does not mark an ear test as passed when its output disappears while playback is pending', async () => {
    let deviceListener: ((snapshot: AudioDeviceSnapshot) => void) | undefined
    let resolveEarTest: (() => void) | undefined
    const deviceService: AudioDeviceServicePort = {
      ...createReadyDeviceService(),
      subscribe: (listener) => {
        deviceListener = listener
        listener(readyDevices)
        return () => undefined
      },
    }
    const audioPlayer = {
      ...createAudioPlayer(),
      playEarTest: vi.fn(() => new Promise<void>((resolve) => { resolveEarTest = resolve })),
      reset: vi.fn(),
    }
    render(
      <FaceToFacePage
        controller={new FaceToFaceController(new DeterministicMockTranslationPort())}
        onBack={() => undefined}
        deviceService={deviceService}
        audioPlayer={audioPlayer}
        microphoneService={createMicrophoneService()}
      />,
    )

    fireEvent.click(screen.getByRole('button', { name: '测试左耳' }))
    deviceListener?.({
      ...readyDevices,
      outputDevices: [],
      selectedOutputDeviceId: null,
      outputDisconnected: true,
    })
    resolveEarTest?.()

    await waitFor(() => expect(audioPlayer.reset).toHaveBeenCalledOnce())
    await waitFor(() => expect(screen.getAllByText('耳机已断开，请重新连接。').length).toBeGreaterThan(0))
    expect(screen.getByRole('button', { name: '测试左耳' })).toBeDisabled()
  })

  it('keeps injected resources owned by the caller after unmount', () => {
    const deviceService = { ...createReadyDeviceService(), dispose: vi.fn() }
    const audioPlayer = { ...createAudioPlayer(), dispose: vi.fn() }
    const microphoneService = { ...createMicrophoneService(), dispose: vi.fn() }
    const { unmount } = render(
      <FaceToFacePage
        controller={new FaceToFaceController(new DeterministicMockTranslationPort())}
        onBack={() => undefined}
        deviceService={deviceService}
        audioPlayer={audioPlayer}
        microphoneService={microphoneService}
      />,
    )

    unmount()

    expect(deviceService.dispose).not.toHaveBeenCalled()
    expect(audioPlayer.dispose).not.toHaveBeenCalled()
    expect(microphoneService.dispose).not.toHaveBeenCalled()
  })

  it('does not dispose owned services during StrictMode effect replay and releases them after unmount', async () => {
    const deviceDispose = vi.spyOn(AudioDeviceService.prototype, 'dispose')
    const playerDispose = vi.spyOn(StereoAudioPlayer.prototype, 'dispose')
    const microphoneDispose = vi.spyOn(MicrophoneService.prototype, 'dispose')
    const { unmount } = render(
      <StrictMode>
        <FaceToFacePage
          controller={new FaceToFaceController(new DeterministicMockTranslationPort())}
          onBack={() => undefined}
        />
      </StrictMode>,
    )

    expect(deviceDispose).not.toHaveBeenCalled()
    expect(playerDispose).not.toHaveBeenCalled()
    expect(microphoneDispose).not.toHaveBeenCalled()

    unmount()

    await waitFor(() => {
      expect(deviceDispose).toHaveBeenCalledOnce()
      expect(playerDispose).toHaveBeenCalledOnce()
      expect(microphoneDispose).toHaveBeenCalledOnce()
    })
  })
})
