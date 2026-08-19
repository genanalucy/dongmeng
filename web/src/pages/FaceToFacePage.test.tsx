import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import type { AudioDeviceServicePort, AudioDeviceSnapshot } from '../audio/AudioDeviceService'
import type { StereoAudioPlayerPort } from '../audio/StereoAudioPlayer'
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
    selectOutput: async () => undefined,
    playEarTest: async () => undefined,
    stop: () => undefined,
    reset: () => undefined,
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
    />,
  )
}

describe('FaceToFacePage', () => {
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

  it('ends an active PTT turn when the window loses focus', () => {
    renderPage()
    const leftButton = screen.getByRole('button', { name: /左耳.*按住说话 中文/i })
    const rightButton = screen.getByRole('button', { name: /右耳.*hold to speak english/i })

    fireEvent.pointerDown(leftButton)
    expect(rightButton).toBeDisabled()
    fireEvent(window, new Event('blur'))

    expect(rightButton).toBeEnabled()
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

  it('releases the audio player when the page unmounts', () => {
    const audioPlayer = {
      ...createAudioPlayer(),
      dispose: vi.fn(),
    }
    const { unmount } = render(
      <FaceToFacePage
        controller={new FaceToFaceController(new DeterministicMockTranslationPort())}
        onBack={() => undefined}
        deviceService={createReadyDeviceService()}
        audioPlayer={audioPlayer}
      />,
    )

    unmount()

    expect(audioPlayer.dispose).toHaveBeenCalledOnce()
  })
})
