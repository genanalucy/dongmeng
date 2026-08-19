import { useCallback, useEffect, useRef, useState } from 'react'
import { EarTestPanel } from '../components/EarTestPanel'
import { PushToTalkButton } from '../components/PushToTalkButton'
import { SubtitlePanel } from '../components/SubtitlePanel'
import { FaceToFaceController, type FaceToFaceSnapshot } from '../face/FaceToFaceController'
import type { Ear, Side } from '../translation/TranslationPort'

interface FaceToFacePageProps {
  readonly controller: FaceToFaceController
  readonly onBack: () => void
}

const initialSnapshot: FaceToFaceSnapshot = {
  state: 'ready',
  leftLanguage: 'zh',
  rightLanguage: 'en',
  activeSide: null,
  subtitles: [],
  errorMessage: null,
}

const demoPhrases: Readonly<Record<Side, string>> = {
  left: '你好，我叫李明。',
  right: 'Nice to meet you.',
}

const stateLabel: Readonly<Record<FaceToFaceSnapshot['state'], string>> = {
  ready: '已就绪 · 可选择一侧按住说话',
  left_speaking: '左侧正在说话 · 右侧已锁定',
  left_translating: '正在向右耳播放模拟英文译文',
  right_speaking: '右侧正在说话 · 左侧已锁定',
  right_translating: '正在向左耳播放模拟中文译文',
  error: '发生错误 · 请重试',
}

export function FaceToFacePage({ controller, onBack }: FaceToFacePageProps): JSX.Element {
  const [snapshot, setSnapshot] = useState<FaceToFaceSnapshot>(initialSnapshot)
  const [testedEars, setTestedEars] = useState<ReadonlySet<Ear>>(() => new Set())
  const playbackTimer = useRef<number | null>(null)

  useEffect(() => controller.subscribe(setSnapshot), [controller])

  const clearPlaybackTimer = useCallback(() => {
    if (playbackTimer.current !== null) {
      window.clearTimeout(playbackTimer.current)
      playbackTimer.current = null
    }
  }, [])

  const stopTurn = useCallback((side: Side) => {
    if (snapshot.activeSide !== side) {
      return
    }
    void controller.stopSpeaking(demoPhrases[side]).then(() => {
      playbackTimer.current = window.setTimeout(() => {
        controller.completePlayback()
        playbackTimer.current = null
      }, 700)
    })
  }, [controller, snapshot.activeSide])

  const cancelActiveTurn = useCallback(() => {
    clearPlaybackTimer()
    controller.cancelActiveTurn()
  }, [clearPlaybackTimer, controller])

  useEffect(() => {
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
      clearPlaybackTimer()
    }
  }, [cancelActiveTurn, clearPlaybackTimer])

  const startTurn = (side: Side): void => {
    controller.startSpeaking(side)
  }

  const testEar = (ear: Ear): void => {
    setTestedEars((current) => new Set([...current, ear]))
  }

  const leftSpeaking = snapshot.state === 'left_speaking'
  const rightSpeaking = snapshot.state === 'right_speaking'
  const controlsLocked = snapshot.state !== 'ready' && !leftSpeaking && !rightSpeaking

  return (
    <main className="face-page">
      <header className="face-header">
        <button type="button" className="back-button" onClick={onBack}>← 返回主页</button>
        <div>
          <p className="eyebrow">FACE TO FACE · HALF DUPLEX</p>
          <h1>面对面翻译</h1>
        </div>
        <span className="mock-badge">模拟翻译，尚未连接火山服务</span>
      </header>

      <section className="connection-strip" aria-label="连接状态">
        <span className="status-dot" aria-hidden="true" />
        <strong>模拟端口已就绪</strong>
        <span>·</span>
        <span>{stateLabel[snapshot.state]}</span>
        <span>·</span>
        <span>固定语言：中文 ↔ English</span>
      </section>

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

      <section className="ptt-grid" aria-label="按住说话控制">
        <PushToTalkButton
          side="left"
          language={snapshot.leftLanguage}
          disabled={controlsLocked || rightSpeaking}
          speaking={leftSpeaking}
          onPointerDown={() => startTurn('left')}
          onPointerUp={() => stopTurn('left')}
          onPointerCancel={() => stopTurn('left')}
        />
        <PushToTalkButton
          side="right"
          language={snapshot.rightLanguage}
          disabled={controlsLocked || leftSpeaking}
          speaking={rightSpeaking}
          onPointerDown={() => startTurn('right')}
          onPointerUp={() => stopTurn('right')}
          onPointerCancel={() => stopTurn('right')}
        />
      </section>

      <p className="half-duplex-note">严格半双工：一侧说话与模拟译文播放期间，另一侧 PTT 保持禁用。</p>
      {snapshot.errorMessage !== null && <p role="alert" className="error-message">{snapshot.errorMessage}</p>}
      <SubtitlePanel subtitles={snapshot.subtitles} />
      <EarTestPanel testedEars={testedEars} onTest={testEar} />
    </main>
  )
}
