import type { PointerEventHandler } from 'react'
import type { LanguageCode, Side } from '../translation/TranslationPort'

interface PushToTalkButtonProps {
  readonly side: Side
  readonly language: LanguageCode
  readonly disabled: boolean
  readonly speaking: boolean
  readonly simulated: boolean
  readonly onPointerDown: PointerEventHandler<HTMLButtonElement>
  readonly onPointerUp: PointerEventHandler<HTMLButtonElement>
  readonly onPointerCancel: PointerEventHandler<HTMLButtonElement>
  readonly onPointerLeave: PointerEventHandler<HTMLButtonElement>
}

const languageLabel: Readonly<Record<LanguageCode, string>> = {
  zh: '中文',
  en: 'English',
}

export function PushToTalkButton({
  side,
  language,
  disabled,
  speaking,
  simulated,
  onPointerDown,
  onPointerUp,
  onPointerCancel,
  onPointerLeave,
}: PushToTalkButtonProps): JSX.Element {
  const instruction = language === 'zh' ? '按住说话' : 'Hold to speak'

  return (
    <button
      type="button"
      className={`ptt-button ${side} ${speaking ? 'speaking' : ''}`}
      disabled={disabled}
      aria-label={`${side === 'left' ? '左耳' : '右耳'} ${instruction} ${languageLabel[language]}`}
      onPointerDown={onPointerDown}
      onPointerUp={onPointerUp}
      onPointerCancel={onPointerCancel}
      onPointerLeave={onPointerLeave}
    >
      <span className="ptt-ear">{side === 'left' ? 'LEFT EAR' : 'RIGHT EAR'}</span>
      <strong>{speaking ? '正在说话…' : instruction}</strong>
      <span>{languageLabel[language]}</span>
      <small>{simulated ? '松开后生成模拟翻译' : '松开后开始实时翻译'}</small>
    </button>
  )
}
