import type { PointerEventHandler } from 'react'
import { languageLabel, type LanguageCode, type Side } from '../translation/TranslationPort'

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
  readonly onLostPointerCapture?: PointerEventHandler<HTMLButtonElement>
  readonly instructionOverride?: string
  readonly detailOverride?: string
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
  onLostPointerCapture,
  instructionOverride,
  detailOverride,
}: PushToTalkButtonProps): JSX.Element {
  const instruction = instructionOverride ?? (language === 'zh' ? '按住说话' : 'Hold to speak')

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
      onLostPointerCapture={onLostPointerCapture}
    >
      <span className="ptt-ear">{side === 'left' ? 'LEFT EAR' : 'RIGHT EAR'}</span>
      <strong>{speaking ? '正在说话…' : instruction}</strong>
      <span>{languageLabel[language]}</span>
      <small>{detailOverride ?? (simulated ? '松开后生成模拟翻译' : '松开后开始实时翻译')}</small>
    </button>
  )
}
