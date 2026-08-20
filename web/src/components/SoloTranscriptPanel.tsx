import type { SoloTranscriptTurn } from '../solo/SoloInterpretationController'

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
  return (
    <section className="subtitle-panel" aria-labelledby="solo-transcript-heading">
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
        <p className="device-description">开始录音后，原文与译文会按 Turn 实时显示在这里。</p>
      ) : (
        <ol className="subtitle-list" aria-label="同传 Turn 列表">
          {turns.map((turn) => (
            <li key={turn.id} data-status={turn.status}>
              <div>
                <strong>Turn {turn.id}</strong>
                <span>{languageLabel[turn.sourceLanguage]} → {languageLabel[turn.targetLanguage]}</span>
                <span>{statusLabel[turn.status]}</span>
              </div>
              <p><strong>原文：</strong>{turn.sourceText || '…'}</p>
              <p><strong>译文：</strong>{turn.translatedText || '…'}</p>
              {turn.error !== null && <p role="alert" className="error-message">{turn.error}</p>}
            </li>
          ))}
        </ol>
      )}
    </section>
  )
}
