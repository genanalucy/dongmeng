import type { SubtitleTurn } from '../face/FaceToFaceController'

interface SubtitlePanelProps {
  readonly subtitles: readonly SubtitleTurn[]
  readonly simulated: boolean
}

export function SubtitlePanel({ subtitles, simulated }: SubtitlePanelProps): JSX.Element {
  return (
    <section className="subtitle-panel" aria-labelledby="subtitle-heading">
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
        <ol className="subtitle-list">
          {subtitles.map((turn) => (
            <li key={turn.id} className={`subtitle-turn ${turn.side}`}>
              <p className="turn-meta">
                {turn.side === 'left' ? 'A · 左耳' : 'B · 右耳'} · {turn.sourceLanguage} → {turn.targetLanguage}
              </p>
              <p>{turn.sourceText}</p>
              <p className="translation-line">→ {turn.translatedText}</p>
              <small>播放目标：{turn.listenerEar === 'left' ? '左耳' : '右耳'}（说话者耳静音）</small>
            </li>
          ))}
        </ol>
      )}
    </section>
  )
}
