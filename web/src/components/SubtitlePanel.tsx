import { useMemo } from 'react'
import type { SubtitleTurn } from '../face/FaceToFaceController'
import { languageLabel } from '../translation/TranslationPort'
import { useFollowLatest } from './useFollowLatest'

interface SubtitlePanelProps {
  readonly subtitles: readonly SubtitleTurn[]
  readonly simulated: boolean
}

export function SubtitlePanel({ subtitles, simulated }: SubtitlePanelProps): JSX.Element {
  const subtitleKeys = useMemo(() => subtitles.map((turn) => turn.id), [subtitles])
  const contentVersion = subtitles.map((turn) => `${turn.id}:${turn.sourceText}:${turn.translatedText}`).join('|')
  const { containerRef, isAtBottom, newItemCount, onScroll, scrollToLatest } = useFollowLatest(subtitleKeys, contentVersion)

  return (
    <section className="subtitle-panel transcript-panel" aria-labelledby="subtitle-heading">
      <div className="section-heading">
        <div>
          <p className="eyebrow">LIVE SUBTITLES</p>
          <h2 id="subtitle-heading">字幕对话</h2>
        </div>
        <span className={simulated ? 'mock-badge' : 'agent-badge'}>
          {simulated ? '模拟字幕' : '实时字幕'}
        </span>
      </div>
      {subtitles.length === 0 ? (
        <p className="empty-subtitle">
          按住任一侧 PTT 并松开，查看本次{simulated ? '模拟' : '实时'}翻译的原文与译文。
        </p>
      ) : (
        <div className="transcript-stream">
          <ol
            ref={containerRef}
            className="subtitle-list"
            aria-label="实时字幕消息"
            aria-live="polite"
            aria-relevant="additions text"
            tabIndex={0}
            onScroll={onScroll}
          >
            {subtitles.map((turn) => (
              <li key={turn.id} className={`subtitle-turn ${turn.side}`}>
                <p className="turn-meta">
                  {turn.side === 'left' ? 'A · 左耳' : 'B · 右耳'} · {languageLabel[turn.sourceLanguage]} → {languageLabel[turn.targetLanguage]}
                </p>
                <p className="source-line">{turn.sourceText}</p>
                <p className="translation-line">{turn.translatedText}</p>
                <small>播放目标：{turn.listenerEar === 'left' ? '左耳' : '右耳'}（说话者耳静音）</small>
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
