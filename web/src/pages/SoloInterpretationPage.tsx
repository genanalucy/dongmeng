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
import { MicrophoneDiagnostics } from '../components/MicrophoneDiagnostics'
import { SoloTranscriptPanel } from '../components/SoloTranscriptPanel'
import {
  type SoloInterpretationSnapshot,
  SoloInterpretationController,
  type SoloTarget,
} from '../solo/SoloInterpretationController'
import { exportTranscriptText } from '../solo/transcriptExport'
import type { AgentHealthSnapshot } from '../translation/AgentHealthService'

export interface SoloInterpretationPageProps {
  readonly controller: SoloInterpretationController
  readonly onBack: () => void
  readonly deviceService?: AudioDeviceServicePort
  readonly audioPlayer?: StereoAudioPlayerPort
  readonly microphoneService?: MicrophoneServicePort
  readonly packetSink?: MutablePcmPacketSink
  readonly agentHealth?: AgentHealthSnapshot
  readonly onCheckAgentHealth?: () => void
}

const initialSnapshot: SoloInterpretationSnapshot = {
  sourceLanguage: 'zh',
  targetLanguage: 'en',
  target: 'both',
  state: 'idle',
  activeTurnId: null,
  turns: [],
  error: null,
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

const defaultAgentHealth: AgentHealthSnapshot = {
  status: 'offline',
  checkedAtMs: null,
  checking: false,
  errorMessage: null,
}

const TURN_DURATION_MS = 25_000
const targetOptions: readonly { readonly value: SoloTarget; readonly label: string }[] = [
  { value: 'both', label: '双耳' },
  { value: 'left', label: '左耳' },
  { value: 'right', label: '右耳' },
  { value: 'captions', label: '仅字幕' },
]

function createDefaultDeviceService(): AudioDeviceServicePort {
  return new AudioDeviceService(createBrowserMediaDevicesPort())
}

function createDefaultAudioPlayer(): StereoAudioPlayerPort {
  return new StereoAudioPlayer(createBrowserAudioContextFactory())
}

function createDefaultMicrophoneService(packetSink: PcmPacketSink): MicrophoneServicePort {
  return new MicrophoneService(createBrowserMicrophoneEnvironment(), packetSink)
}

export function SoloInterpretationPage({
  controller,
  onBack,
  deviceService: providedDeviceService,
  audioPlayer: providedAudioPlayer,
  microphoneService: providedMicrophoneService,
  packetSink: providedPacketSink,
  agentHealth = defaultAgentHealth,
  onCheckAgentHealth,
}: SoloInterpretationPageProps): JSX.Element {
  const [packetSink] = useState(() => providedPacketSink ?? new MutablePcmPacketSink())
  const [deviceService] = useState<AudioDeviceServicePort>(() => providedDeviceService ?? createDefaultDeviceService())
  const [audioPlayer] = useState<StereoAudioPlayerPort>(() => providedAudioPlayer ?? createDefaultAudioPlayer())
  const [microphoneService] = useState<MicrophoneServicePort>(
    () => providedMicrophoneService ?? createDefaultMicrophoneService(packetSink),
  )
  const [snapshot, setSnapshot] = useState(initialSnapshot)
  const [deviceSnapshot, setDeviceSnapshot] = useState(initialDeviceSnapshot)
  const [microphoneSnapshot, setMicrophoneSnapshot] = useState(initialMicrophoneSnapshot)
  const [deviceBusy, setDeviceBusy] = useState(false)
  const [deviceActionError, setDeviceActionError] = useState<string | null>(null)
  const [captureError, setCaptureError] = useState<string | null>(null)
  const [actionMessage, setActionMessage] = useState<string | null>(null)
  const runningRef = useRef(false)
  const activeTurnIdRef = useRef<number | null>(null)
  const turnTimerRef = useRef<number | null>(null)
  const captureGenerationRef = useRef(0)
  const deviceLifecycleRef = useRef(0)
  const microphoneLifecycleRef = useRef(0)
  const playerLifecycleRef = useRef(0)

  const ownsDeviceService = providedDeviceService === undefined
  const ownsMicrophoneService = providedMicrophoneService === undefined
  const ownsAudioPlayer = providedAudioPlayer === undefined

  useEffect(() => controller.subscribe(setSnapshot), [controller])

  useEffect(() => {
    const lifecycleRef = deviceLifecycleRef
    const generation = ++lifecycleRef.current
    const unsubscribe = deviceService.subscribe(setDeviceSnapshot)
    return () => {
      unsubscribe()
      if (ownsDeviceService) {
        queueMicrotask(() => {
          if (lifecycleRef.current === generation) deviceService.dispose()
        })
      }
    }
  }, [deviceService, ownsDeviceService])

  useEffect(() => {
    const lifecycleRef = microphoneLifecycleRef
    const generation = ++lifecycleRef.current
    const unsubscribe = microphoneService.subscribe(setMicrophoneSnapshot)
    return () => {
      unsubscribe()
      if (ownsMicrophoneService) {
        queueMicrotask(() => {
          if (lifecycleRef.current === generation) microphoneService.dispose()
        })
      }
    }
  }, [microphoneService, ownsMicrophoneService])

  const clearTurnTimer = useCallback((): void => {
    if (turnTimerRef.current !== null) {
      window.clearTimeout(turnTimerRef.current)
      turnTimerRef.current = null
    }
  }, [])

  const cancelAll = useCallback((): void => {
    runningRef.current = false
    captureGenerationRef.current += 1
    clearTurnTimer()
    activeTurnIdRef.current = null
    packetSink.setSink(null)
    microphoneService.stop()
    controller.cancelAll()
    audioPlayer.stop()
  }, [audioPlayer, clearTurnTimer, controller, microphoneService, packetSink])

  useEffect(() => {
    const lifecycleRef = playerLifecycleRef
    const generation = ++lifecycleRef.current
    const onVisibilityChange = (): void => {
      if (document.visibilityState === 'hidden') cancelAll()
    }
    document.addEventListener('visibilitychange', onVisibilityChange)
    return () => {
      document.removeEventListener('visibilitychange', onVisibilityChange)
      cancelAll()
      if (ownsAudioPlayer) {
        queueMicrotask(() => {
          if (lifecycleRef.current === generation) audioPlayer.dispose()
        })
      }
    }
  }, [audioPlayer, cancelAll, ownsAudioPlayer])

  useEffect(() => {
    if (runningRef.current && deviceSnapshot.selectedInputDeviceId === null) {
      cancelAll()
      queueMicrotask(() => setCaptureError('输入麦克风已断开，请重新选择输入设备。'))
    }
  }, [cancelAll, deviceSnapshot.selectedInputDeviceId])

  useEffect(() => {
    if (snapshot.target !== 'captions' && deviceSnapshot.outputDisconnected) {
      cancelAll()
      audioPlayer.reset()
      queueMicrotask(() => setCaptureError('音频输出已断开，请重新选择输出设备。'))
    }
  }, [audioPlayer, cancelAll, deviceSnapshot.outputDisconnected, snapshot.target])

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
    if (deviceId !== '') await runDeviceAction(() => deviceService.selectInput(deviceId))
  }, [deviceService, runDeviceAction])

  const selectOutput = useCallback(async (deviceId: string): Promise<void> => {
    if (deviceId === '') {
      deviceService.clearOutputSelection()
      return
    }
    await runDeviceAction(async () => {
      if (!audioPlayer.supportsOutputSelection) {
        throw new Error('此浏览器无法直接选择音频输出，请使用支持 setSinkId 的浏览器。')
      }
      await audioPlayer.selectOutput(deviceId)
      await deviceService.selectOutput(deviceId)
      setCaptureError(null)
    })
  }, [audioPlayer, deviceService, runDeviceAction])

  const beginTurn = useCallback((): boolean => {
    const turnId = controller.startTurn()
    if (turnId === null) return false
    activeTurnIdRef.current = turnId
    packetSink.setSink({ push: (packet) => controller.pushAudio(turnId, packet) })
    return true
  }, [controller, packetSink])

  const scheduleRoll = useCallback(function roll(): void {
    clearTurnTimer()
    turnTimerRef.current = window.setTimeout(() => {
      const turnId = activeTurnIdRef.current
      if (!runningRef.current || turnId === null) return
      packetSink.setSink(null)
      activeTurnIdRef.current = null
      controller.finishTurn(turnId, '')
      if (beginTurn()) roll()
    }, TURN_DURATION_MS)
  }, [beginTurn, clearTurnTimer, controller, packetSink])

  const outputReady = snapshot.target === 'captions'
    || deviceSnapshot.selectedOutputDeviceId !== null && !deviceSnapshot.outputDisconnected
  const devicesReady = deviceSnapshot.microphonePermissionGranted
    && deviceSnapshot.selectedInputDeviceId !== null
    && outputReady
  const agentOnline = agentHealth.status === 'online'

  const start = (): void => {
    const inputDeviceId = deviceSnapshot.selectedInputDeviceId
    if (runningRef.current || !devicesReady || inputDeviceId === null || !agentOnline) return
    setCaptureError(null)
    setActionMessage(null)
    controller.cancelAll()
    runningRef.current = true
    if (!beginTurn()) {
      runningRef.current = false
      return
    }
    scheduleRoll()
    const generation = ++captureGenerationRef.current
    void microphoneService.start(inputDeviceId).catch((error: unknown) => {
      if (captureGenerationRef.current !== generation) return
      cancelAll()
      setCaptureError(error instanceof Error ? error.message : '无法开始麦克风采集。')
    })
  }

  const pause = (): void => {
    if (!runningRef.current) return
    runningRef.current = false
    captureGenerationRef.current += 1
    clearTurnTimer()
    packetSink.setSink(null)
    const turnId = activeTurnIdRef.current
    activeTurnIdRef.current = null
    if (turnId !== null) controller.finishTurn(turnId, '')
    controller.pause()
    microphoneService.stop()
  }

  const resume = (): void => {
    const inputDeviceId = deviceSnapshot.selectedInputDeviceId
    if (snapshot.state !== 'paused' || inputDeviceId === null || !devicesReady || !agentOnline) return
    if (!controller.resume()) return
    runningRef.current = true
    if (!beginTurn()) {
      runningRef.current = false
      return
    }
    scheduleRoll()
    const generation = ++captureGenerationRef.current
    void microphoneService.start(inputDeviceId).catch((error: unknown) => {
      if (captureGenerationRef.current !== generation) return
      cancelAll()
      setCaptureError(error instanceof Error ? error.message : '无法恢复麦克风采集。')
    })
  }

  const finish = (): void => {
    runningRef.current = false
    captureGenerationRef.current += 1
    clearTurnTimer()
    packetSink.setSink(null)
    activeTurnIdRef.current = null
    microphoneService.stop()
    void controller.finishAll('').then(() => audioPlayer.whenIdle())
  }

  const copyTranscript = async (): Promise<void> => {
    try {
      await navigator.clipboard.writeText(exportTranscriptText(snapshot.turns))
      setActionMessage('已复制同传记录。')
    } catch {
      setActionMessage('复制失败，请检查浏览器剪贴板权限。')
    }
  }

  const exportTranscript = (): void => {
    const blob = new Blob([exportTranscriptText(snapshot.turns)], { type: 'text/plain;charset=utf-8' })
    const url = URL.createObjectURL(blob)
    const anchor = document.createElement('a')
    anchor.href = url
    anchor.download = 'solo-interpretation.txt'
    anchor.click()
    URL.revokeObjectURL(url)
    setActionMessage('已导出 TXT。')
  }

  const isCapturing = snapshot.state === 'capturing'
  const canConfigure = snapshot.state === 'idle' || snapshot.state === 'paused'
  const healthLabel = agentHealth.checking ? 'CHECKING' : agentOnline ? 'ONLINE' : 'OFFLINE'

  return (
    <main className="face-page">
      <header className="face-header">
        <button type="button" className="back-button" onClick={onBack}>← 返回主页</button>
        <div><p className="eyebrow">SOLO · CONTINUOUS INTERPRETATION</p><h1>单人同传</h1></div>
        <span className="agent-badge">Local Agent</span>
      </header>

      <section className="connection-strip" aria-label="连接状态">
        <span className={`status-dot ${agentHealth.status}`} aria-hidden="true" />
        <strong>Local Agent {healthLabel}</strong>
        <button type="button" className="health-check-button" onClick={onCheckAgentHealth}>手动检测</button>
      </section>
      {!agentOnline && !agentHealth.checking && (
        <p role="alert" className="error-message">Local Agent 离线，无法开始同传。请启动 Agent 后手动检测。</p>
      )}

      <AudioDevicePanel
        snapshot={deviceSnapshot}
        outputSelectionSupported={audioPlayer.supportsOutputSelection}
        busy={deviceBusy}
        actionError={deviceActionError}
        onRequestPermission={requestPermission}
        onRefresh={refreshDevices}
        onSelectInput={selectInput}
        onSelectOutput={selectOutput}
        title="单人同传准备"
        description={snapshot.target === 'captions'
          ? '授权并选择输入麦克风即可开始；仅字幕模式不要求输出设备。'
          : '授权麦克风，并选择输入设备与同传语音输出设备。'}
        requireOutput={snapshot.target !== 'captions'}
      />

      <section className="language-panel" aria-label="同传语言与播放目标">
        <div className="participant-card left-card"><p>源语言</p><strong>{snapshot.sourceLanguage === 'zh' ? '中文' : 'English'}</strong></div>
        <button type="button" className="swap-button" aria-label="交换源语言和目标语言" disabled={!canConfigure} onClick={() => controller.swapLanguages()}>⇄</button>
        <div className="participant-card right-card"><p>目标语言</p><strong>{snapshot.targetLanguage === 'en' ? 'English' : '中文'}</strong></div>
        <fieldset disabled={!canConfigure}>
          <legend>播放目标</legend>
          {targetOptions.map((option) => (
            <label key={option.value}>
              <input type="radio" name="solo-target" value={option.value} checked={snapshot.target === option.value} onChange={() => controller.setTarget(option.value)} />
              {option.label}
            </label>
          ))}
        </fieldset>
      </section>

      <MicrophoneDiagnostics snapshot={microphoneSnapshot} />

      <section className="translation-mode-panel" aria-label="连续录音控制">
        {!isCapturing && snapshot.state !== 'paused' && (
          <button type="button" className="start-auto-button" disabled={!devicesReady || !agentOnline || snapshot.state === 'stopping'} onClick={start}>开始同传</button>
        )}
        {isCapturing && <button type="button" className="secondary-button" onClick={pause}>暂停</button>}
        {snapshot.state === 'paused' && <button type="button" className="start-auto-button" disabled={!devicesReady || !agentOnline} onClick={resume}>恢复</button>}
        {(isCapturing || snapshot.state === 'paused' || snapshot.state === 'stopping') && <button type="button" className="stop-auto-button" onClick={finish}>结束</button>}
        <span>每 25 秒自动滚动 Turn，后台翻译不会中断麦克风采集。</span>
      </section>

      {captureError !== null && <p role="alert" className="error-message">{captureError}</p>}
      {snapshot.error !== null && <p role="alert" className="error-message">{snapshot.error}</p>}
      <SoloTranscriptPanel
        turns={snapshot.turns}
        actionMessage={actionMessage}
        onClear={() => { controller.clearTranscript(); audioPlayer.clear(); setActionMessage(null) }}
        onCopy={copyTranscript}
        onExport={exportTranscript}
      />
    </main>
  )
}
