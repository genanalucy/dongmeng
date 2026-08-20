import { useMemo } from 'react'
import type { SoloTranscriptTurn } from '../solo/SoloInterpretationController'
import { useFollowLatest } from './useFollowLatest'

interface SoloTranscriptPanelProps {
  readonly turns: readonly SoloTranscriptTurn[]
  readonly actionMessage: string | null
  readonly onClear: () => void
  readonly onCopy: () => Promise<void>
  readonly onExport: () => void
}

const languageLabel = { zh: '中文', en: 'English' } as const

const statusLabel: Readonly<Record<SoloTranscriptTurn['status'], string>> = {
  capturing: '实时转写中',
  stopping: '翻译处理中',
  finished: '已完成',
  error: '失败',
}

export function SoloTranscriptPanel({
  turns,
  actionMessage,
  onClear,
  onCopy,
  onExport,
}: SoloTranscriptPanelProps): JSX.Element {
  const turnKeys = useMemo(() => turns.map((turn) => turn.id), [turns])
  const contentVersion = turns.map((turn) => `${turn.id}:${turn.status}:${turn.sourceText}:${turn.translatedText}`).join('|')
  const { containerRef, isAtBottom, newItemCount, onScroll, scrollToLatest } = useFollowLatest(turnKeys, contentVersion)

  return (
    <section className="subtitle-panel transcript-panel" aria-labelledby="solo-transcript-heading">
      <div className="section-heading">
        <div>
          <p className="eyebrow">LIVE TRANSCRIPT</p>
          <h2 id="solo-transcript-heading">实时同传记录</h2>
        </div>
        <div className="device-actions">
          <button type="button" className="back-button" onClick={() => { void onCopy() }} disabled={turns.length === 0}>复制</button>
          <button type="button" className="back-button" onClick={onExport} disabled={turns.length === 0}>导出 TXT</button>
          <button type="button" className="secondary-button" onClick={onClear} disabled={turns.length === 0}>清空</button>
        </div>
      </div>
      {actionMessage !== null && <p role="status">{actionMessage}</p>}
      {turns.length === 0 ? (
        <p className="empty-subtitle">开始录音后，原文与译文会实时显示在这里。</p>
      ) : (
        <div className="transcript-stream">
          <ol
            ref={containerRef}
            className="subtitle-list"
            aria-label="实时同传消息"
            aria-live="polite"
            aria-relevant="additions text"
            tabIndex={0}
            onScroll={onScroll}
          >
            {turns.map((turn) => (
              <li key={turn.id} className="transcript-turn" data-status={turn.status}>
                <div className="turn-header">
                  <strong>对话 {turn.id}</strong>
                  <span>{languageLabel[turn.sourceLanguage]} → {languageLabel[turn.targetLanguage]}</span>
                  <span>{statusLabel[turn.status]}</span>
                </div>
                <p className="source-line"><span>原文</span>{turn.sourceText || '正在聆听…'}</p>
                <p className="translation-line"><span>译文</span>{turn.translatedText || '等待翻译…'}</p>
                {turn.error !== null && <p role="alert" className="error-message">{turn.error}</p>}
              </li>
            ))}
          </ol>
          {!isAtBottom && (
            <div className="latest-message-control" role="status">
              <button type="button" onClick={scrollToLatest}>
                {newItemCount > 0 ? `${newItemCount} 条新消息` : '回到最新'}
                <span aria-hidden="true">↓</span>
              </button>
            </div>
          )}
        </div>
      )}
    </section>
  )
}
