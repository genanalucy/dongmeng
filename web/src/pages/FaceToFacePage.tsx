import { useCallback, useEffect, useRef, useState } from 'react'
import {
  AudioDeviceService,
  createBrowserMediaDevicesPort,
  type AudioDeviceServicePort,
  type AudioDeviceSnapshot,
} from '../audio/AudioDeviceService'
import {
  createBrowserAudioContextFactory,
  StereoAudioPlayer,
  type StereoAudioPlayerPort,
} from '../audio/StereoAudioPlayer'
import {
  createBrowserMicrophoneEnvironment,
  MicrophoneService,
  MutablePcmPacketSink,
  type MicrophoneServicePort,
  type MicrophoneSnapshot,
} from '../audio/MicrophoneService'
import type { PcmPacketSink } from '../audio/PcmCapturePipeline'
import { AudioDevicePanel } from '../components/AudioDevicePanel'
import { EarTestPanel } from '../components/EarTestPanel'
import { MicrophoneDiagnostics } from '../components/MicrophoneDiagnostics'
import { PushToTalkButton } from '../components/PushToTalkButton'
import { SubtitlePanel } from '../components/SubtitlePanel'
import { FaceToFaceController, type FaceToFaceSnapshot } from '../face/FaceToFaceController'
import type { AgentHealthSnapshot } from '../translation/AgentHealthService'
import type { Ear, Side } from '../translation/TranslationPort'

type TranslationMode = 'local' | 'mock'

interface FaceToFacePageProps {
  readonly controller: FaceToFaceController
  readonly onBack: () => void
  readonly deviceService?: AudioDeviceServicePort
  readonly audioPlayer?: StereoAudioPlayerPort
  readonly microphoneService?: MicrophoneServicePort
  readonly packetSink?: MutablePcmPacketSink
  readonly translationMode?: TranslationMode
  readonly agentHealth?: AgentHealthSnapshot
  readonly onSelectTranslationMode?: (mode: TranslationMode) => void
  readonly onCheckAgentHealth?: () => void
}

const initialSnapshot: FaceToFaceSnapshot = {
  state: 'ready',
  leftLanguage: 'zh',
  rightLanguage: 'en',
  activeSide: null,
  subtitles: [],
  errorMessage: null,
}

const initialDeviceSnapshot: AudioDeviceSnapshot = {
  inputDevices: [],
  outputDevices: [],
  selectedInputDeviceId: null,
  selectedOutputDeviceId: null,
  microphonePermissionGranted: false,
  outputDisconnected: false,
  errorMessage: null,
}

const initialMicrophoneSnapshot: MicrophoneSnapshot = {
  state: 'idle',
  inputSampleRate: null,
  astSampleRate: 16_000,
  packetDurationMs: 80,
  latestPacketBytes: 0,
  packetCount: 0,
  audioLevel: 0,
  errorMessage: null,
}

const MAX_PTT_DURATION_MS = 25_000

const demoPhrases: Readonly<Record<Side, string>> = {
  left: '你好，我叫李明。',
  right: 'Nice to meet you.',
}

const stateLabel: Readonly<Record<FaceToFaceSnapshot['state'], string>> = {
  ready: '已就绪 · 可选择一侧按住说话',
  left_speaking: '左侧正在说话 · 右侧已锁定',
  left_translating: '正在向右耳播放英文译文',
  right_speaking: '右侧正在说话 · 左侧已锁定',
  right_translating: '正在向左耳播放中文译文',
  error: '发生错误 · 请重新准备设备后重试',
}

function createDefaultDeviceService(): AudioDeviceServicePort {
  return new AudioDeviceService(createBrowserMediaDevicesPort())
}

function createDefaultAudioPlayer(): StereoAudioPlayerPort {
  return new StereoAudioPlayer(createBrowserAudioContextFactory())
}

function createDefaultMicrophoneService(packetSink: PcmPacketSink): MicrophoneServicePort {
  return new MicrophoneService(createBrowserMicrophoneEnvironment(), packetSink)
}

export function FaceToFacePage({
  controller,
  onBack,
  deviceService: providedDeviceService,
  audioPlayer: providedAudioPlayer,
  microphoneService: providedMicrophoneService,
  packetSink: providedPacketSink,
  translationMode = 'mock',
  agentHealth = {
    status: 'offline',
    checkedAtMs: null,
    checking: false,
    errorMessage: null,
  },
  onSelectTranslationMode,
  onCheckAgentHealth,
}: FaceToFacePageProps): JSX.Element {
  const [packetSink] = useState(() => providedPacketSink ?? new MutablePcmPacketSink())
  const [deviceService] = useState<AudioDeviceServicePort>(
    () => providedDeviceService ?? createDefaultDeviceService(),
  )
  const [audioPlayer] = useState<StereoAudioPlayerPort>(
    () => providedAudioPlayer ?? createDefaultAudioPlayer(),
  )
  const [microphoneService] = useState<MicrophoneServicePort>(
    () => providedMicrophoneService ?? createDefaultMicrophoneService(packetSink),
  )
  const ownsDeviceService = providedDeviceService === undefined
  const ownsAudioPlayer = providedAudioPlayer === undefined
  const ownsMicrophoneService = providedMicrophoneService === undefined
  const deviceServiceLifecycleGeneration = useRef(0)
  const audioPlayerLifecycleGeneration = useRef(0)
  const microphoneServiceLifecycleGeneration = useRef(0)
  const [snapshot, setSnapshot] = useState<FaceToFaceSnapshot>(initialSnapshot)
  const [deviceSnapshot, setDeviceSnapshot] = useState<AudioDeviceSnapshot>(initialDeviceSnapshot)
  const [microphoneSnapshot, setMicrophoneSnapshot] = useState<MicrophoneSnapshot>(initialMicrophoneSnapshot)
  const [testedEars, setTestedEars] = useState<ReadonlySet<Ear>>(() => new Set())
  const [deviceBusy, setDeviceBusy] = useState(false)
  const [deviceActionError, setDeviceActionError] = useState<string | null>(null)
  const [earTestError, setEarTestError] = useState<string | null>(null)
  const playbackTimer = useRef<number | null>(null)
  const playbackGeneration = useRef(0)
  const captureGeneration = useRef(0)
  const activeCaptureSide = useRef<Side | null>(null)
  const deviceSnapshotRef = useRef<AudioDeviceSnapshot>(initialDeviceSnapshot)

  useEffect(() => controller.subscribe(setSnapshot), [controller])

  useEffect(() => {
    const lifecycleGeneration = microphoneServiceLifecycleGeneration
    const generation = ++lifecycleGeneration.current
    const unsubscribe = microphoneService.subscribe(setMicrophoneSnapshot)
    return () => {
      unsubscribe()
      if (!ownsMicrophoneService) {
        return
      }
      queueMicrotask(() => {
        if (lifecycleGeneration.current === generation) {
          microphoneService.dispose()
        }
      })
    }
  }, [microphoneService, ownsMicrophoneService])

  useEffect(() => {
    const lifecycleGeneration = deviceServiceLifecycleGeneration
    const generation = ++lifecycleGeneration.current
    const unsubscribe = deviceService.subscribe((nextSnapshot) => {
      deviceSnapshotRef.current = nextSnapshot
      setDeviceSnapshot(nextSnapshot)
    })
    return () => {
      unsubscribe()
      if (!ownsDeviceService) {
        return
      }
      queueMicrotask(() => {
        if (lifecycleGeneration.current === generation) {
          deviceService.dispose()
        }
      })
    }
  }, [deviceService, ownsDeviceService])

  const clearPlaybackTimer = useCallback(() => {
    if (playbackTimer.current !== null) {
      window.clearTimeout(playbackTimer.current)
      playbackTimer.current = null
    }
  }, [])

  const stopCapture = useCallback(() => {
    if (activeCaptureSide.current === null) {
      return
    }
    activeCaptureSide.current = null
    captureGeneration.current += 1
    clearPlaybackTimer()
    microphoneService.stop()
    packetSink.setSink(null)
  }, [clearPlaybackTimer, microphoneService, packetSink])

  useEffect(() => {
    const side = activeCaptureSide.current
    if (side !== null && controller.getSnapshot().state !== `${side}_speaking`) {
      stopCapture()
    }
  }, [controller, snapshot.state, stopCapture])

  const cancelActiveTurn = useCallback(() => {
    playbackGeneration.current += 1
    stopCapture()
    audioPlayer.stop()
    controller.cancelActiveTurn()
  }, [audioPlayer, controller, stopCapture])

  useEffect(() => {
    const lifecycleGeneration = audioPlayerLifecycleGeneration
    const generation = ++lifecycleGeneration.current
    const onBlur = (): void => cancelActiveTurn()
    const onVisibilityChange = (): void => {
      if (document.visibilityState === 'hidden') {
        cancelActiveTurn()
      }
    }
    window.addEventListener('blur', onBlur)
    document.addEventListener('visibilitychange', onVisibilityChange)
    return () => {
      window.removeEventListener('blur', onBlur)
      document.removeEventListener('visibilitychange', onVisibilityChange)
      playbackGeneration.current += 1
      clearPlaybackTimer()
      audioPlayer.stop()
      if (!ownsAudioPlayer) {
        return
      }
      queueMicrotask(() => {
        if (lifecycleGeneration.current === generation) {
          audioPlayer.dispose()
        }
      })
    }
  }, [audioPlayer, cancelActiveTurn, clearPlaybackTimer, ownsAudioPlayer])

  const devicesReady = deviceSnapshot.microphonePermissionGranted
    && deviceSnapshot.selectedInputDeviceId !== null
    && deviceSnapshot.selectedOutputDeviceId !== null
    && !deviceSnapshot.outputDisconnected

  useEffect(() => {
    if (!deviceSnapshot.outputDisconnected) {
      return
    }
    let active = true
    playbackGeneration.current += 1
    stopCapture()
    audioPlayer.reset()
    queueMicrotask(() => {
      if (!active) {
        return
      }
      setTestedEars(new Set())
      setEarTestError('耳机已断开，请重新连接。')
      controller.reportExternalError('耳机已断开，请重新连接。')
    })
    return () => {
      active = false
    }
  }, [audioPlayer, controller, deviceSnapshot.outputDisconnected, stopCapture])

  useEffect(() => {
    if (!devicesReady || !controller.recoverFromExternalError()) {
      return
    }
    let active = true
    playbackGeneration.current += 1
    stopCapture()
    audioPlayer.stop()
    queueMicrotask(() => {
      if (active) {
        setEarTestError(null)
      }
    })
    return () => {
      active = false
    }
  }, [audioPlayer, controller, devicesReady, stopCapture])

  const runDeviceAction = useCallback(async (action: () => Promise<void>): Promise<void> => {
    setDeviceBusy(true)
    setDeviceActionError(null)
    try {
      await action()
    } catch (error: unknown) {
      setDeviceActionError(error instanceof Error ? error.message : '设备操作失败。')
    } finally {
      setDeviceBusy(false)
    }
  }, [])

  const requestPermission = useCallback(async (): Promise<void> => {
    await runDeviceAction(async () => {
      await deviceService.requestPermission()
      await deviceService.refreshDevices()
    })
  }, [deviceService, runDeviceAction])

  const refreshDevices = useCallback(async (): Promise<void> => {
    await runDeviceAction(() => deviceService.refreshDevices())
  }, [deviceService, runDeviceAction])

  const selectInput = useCallback(async (deviceId: string): Promise<void> => {
    if (deviceId.length === 0) {
      return
    }
    await runDeviceAction(() => deviceService.selectInput(deviceId))
  }, [deviceService, runDeviceAction])

  const selectOutput = useCallback(async (deviceId: string): Promise<void> => {
    if (deviceId.length === 0) {
      deviceService.clearOutputSelection()
      return
    }
    await runDeviceAction(async () => {
      if (!audioPlayer.supportsOutputSelection) {
        throw new Error('此浏览器无法直接选择音频输出，不能播放到默认扬声器。请使用支持 setSinkId 的浏览器。')
      }
      try {
        await audioPlayer.selectOutput(deviceId)
      } catch (error: unknown) {
        audioPlayer.reset()
        throw error
      }
      await deviceService.selectOutput(deviceId)
      setTestedEars(new Set())
      setEarTestError(null)
    })
  }, [audioPlayer, deviceService, runDeviceAction])

  const stopTurn = useCallback((side: Side) => {
    clearPlaybackTimer()
    const current = controller.getSnapshot()
    if (current.activeSide !== side || current.state !== `${side}_speaking`) {
      return
    }
    stopCapture()
    void controller.stopSpeaking(demoPhrases[side])
  }, [clearPlaybackTimer, controller, stopCapture])

  const startTurn = (side: Side): void => {
    const selectedInputDeviceId = deviceSnapshot.selectedInputDeviceId
    if (!devicesReady || selectedInputDeviceId === null
      || translationMode === 'local' && agentHealth.status !== 'online') {
      return
    }
    if (!controller.startSpeaking(side)) {
      return
    }
    const generation = ++captureGeneration.current
    activeCaptureSide.current = side
    packetSink.setSink({ push: (packet) => controller.pushAudio(packet) })
    clearPlaybackTimer()
    playbackTimer.current = window.setTimeout(() => stopTurn(side), MAX_PTT_DURATION_MS)
    void microphoneService.start(selectedInputDeviceId).catch((error: unknown) => {
      if (captureGeneration.current !== generation) {
        return
      }
      stopCapture()
      controller.reportExternalError(
        error instanceof Error ? error.message : '无法开始麦克风采集。',
      )
    })
  }

  const testEar = useCallback(async (ear: Ear): Promise<void> => {
    const selectedOutputDeviceId = deviceSnapshotRef.current.selectedOutputDeviceId
    if (selectedOutputDeviceId === null || deviceSnapshotRef.current.outputDisconnected) {
      setEarTestError('请先选择已连接的耳机输出设备，再测试左右耳。')
      return
    }
    try {
      setEarTestError(null)
      await audioPlayer.playEarTest(ear)
      if (deviceSnapshotRef.current.selectedOutputDeviceId !== selectedOutputDeviceId
        || deviceSnapshotRef.current.outputDisconnected) {
        setEarTestError('耳机已断开，请重新连接。')
        return
      }
      setTestedEars((current) => new Set([...current, ear]))
    } catch (error: unknown) {
      if (deviceSnapshotRef.current.selectedOutputDeviceId !== selectedOutputDeviceId
        || deviceSnapshotRef.current.outputDisconnected) {
        setEarTestError('耳机已断开，请重新连接。')
        return
      }
      setEarTestError(error instanceof Error ? error.message : '测试音播放失败。')
    }
  }, [audioPlayer])

  const leftSpeaking = snapshot.state === 'left_speaking'
  const rightSpeaking = snapshot.state === 'right_speaking'
  const localAgentUnavailable = translationMode === 'local' && agentHealth.status !== 'online'
  const controlsLocked = !devicesReady || localAgentUnavailable
    || snapshot.state !== 'ready' && !leftSpeaking && !rightSpeaking
  const modeLabel = translationMode === 'local' ? 'Local Agent 模式' : '模拟模式'
  const healthLabel = agentHealth.checking
    ? 'CHECKING'
    : agentHealth.status === 'online' ? 'ONLINE' : 'OFFLINE'

  return (
    <main className="face-page">
      <header className="face-header">
        <button type="button" className="back-button" onClick={onBack}>← 返回主页</button>
        <div>
          <p className="eyebrow">FACE TO FACE · HALF DUPLEX</p>
          <h1>面对面翻译</h1>
        </div>
        <span className={translationMode === 'local' ? 'agent-badge' : 'mock-badge'}>{modeLabel}</span>
      </header>

      <section className="connection-strip" aria-label="连接状态">
        <span className={`status-dot ${agentHealth.status}`} aria-hidden="true" />
        <strong>{modeLabel}</strong>
        <span>·</span>
        <span>Agent {healthLabel}</span>
        <button type="button" className="health-check-button" onClick={onCheckAgentHealth}>手动检测</button>
        <span>·</span>
        <span>{stateLabel[snapshot.state]}</span>
        <span>·</span>
        <span>固定语言：中文 ↔ English</span>
      </section>

      <section className="translation-mode-panel" aria-label="翻译模式选择">
        <strong>翻译模式</strong>
        <button
          type="button"
          className={translationMode === 'local' ? 'mode-selected' : 'mode-option'}
          onClick={() => onSelectTranslationMode?.('local')}
          disabled={snapshot.state !== 'ready'}
        >Local Agent 模式</button>
        <button
          type="button"
          className={translationMode === 'mock' ? 'mode-selected' : 'mode-option'}
          onClick={() => onSelectTranslationMode?.('mock')}
          disabled={snapshot.state !== 'ready'}
        >模拟模式</button>
        {localAgentUnavailable && !agentHealth.checking && (
          <p role="alert" className="error-message">
            Local Agent 离线，无法开始翻译。{agentHealth.errorMessage === null
              ? '请启动 Agent 后手动检测。'
              : `检测失败：${agentHealth.errorMessage}。`}不会自动切换到模拟模式。
          </p>
        )}
      </section>

      <AudioDevicePanel
        snapshot={deviceSnapshot}
        outputSelectionSupported={audioPlayer.supportsOutputSelection}
        busy={deviceBusy}
        actionError={deviceActionError}
        onRequestPermission={requestPermission}
        onRefresh={refreshDevices}
        onSelectInput={selectInput}
        onSelectOutput={selectOutput}
      />

      <section className="language-panel" aria-label="语言和耳机映射">
        <div className="participant-card left-card">
          <p>LEFT · 左耳</p>
          <strong>{snapshot.leftLanguage === 'zh' ? '中文' : 'English'}</strong>
          <small>讲话时静音</small>
        </div>
        <button
          type="button"
          className="swap-button"
          aria-label="交换左右语言"
          disabled={snapshot.state !== 'ready'}
          onClick={() => controller.swapLanguages()}
        >
          ⇄
        </button>
        <div className="participant-card right-card">
          <p>RIGHT · 右耳</p>
          <strong>{snapshot.rightLanguage === 'en' ? 'English' : '中文'}</strong>
          <small>接收对方译文</small>
        </div>
      </section>

      <MicrophoneDiagnostics snapshot={microphoneSnapshot} />

      <section className="ptt-grid" aria-label="按住说话控制">
        <PushToTalkButton
          side="left"
          language={snapshot.leftLanguage}
          disabled={controlsLocked || rightSpeaking}
          speaking={leftSpeaking}
          simulated={translationMode === 'mock'}
          onPointerDown={() => startTurn('left')}
          onPointerUp={() => stopTurn('left')}
          onPointerCancel={() => stopTurn('left')}
          onPointerLeave={() => stopTurn('left')}
        />
        <PushToTalkButton
          side="right"
          language={snapshot.rightLanguage}
          disabled={controlsLocked || leftSpeaking}
          speaking={rightSpeaking}
          simulated={translationMode === 'mock'}
          onPointerDown={() => startTurn('right')}
          onPointerUp={() => stopTurn('right')}
          onPointerCancel={() => stopTurn('right')}
          onPointerLeave={() => stopTurn('right')}
        />
      </section>

      <p className="half-duplex-note">严格半双工：设备准备完成前不能开始；一侧说话与译文处理期间，另一侧 PTT 保持禁用。</p>
      {snapshot.errorMessage !== null && <p role="alert" className="error-message">{snapshot.errorMessage}</p>}
      <SubtitlePanel subtitles={snapshot.subtitles} simulated={translationMode === 'mock'} />
      <EarTestPanel
        testedEars={testedEars}
        disabled={!devicesReady || !audioPlayer.supportsOutputSelection}
        errorMessage={earTestError}
        onTest={testEar}
      />
    </main>
  )
}
