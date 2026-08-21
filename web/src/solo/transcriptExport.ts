import { languageLabel } from '../translation/TranslationPort'
import type { SoloTranscriptTurn } from './SoloInterpretationController'

export interface TranscriptExportOptions {
  readonly title?: string
  readonly includeTurnNumbers?: boolean
}

export type TranscriptExportTurn = Pick<
  SoloTranscriptTurn,
  'id' | 'sourceLanguage' | 'targetLanguage' | 'sourceText' | 'translatedText'
>

function normalizeText(text: string): string {
  return text.replace(/\r\n?/g, '\n').trim()
}

function indentContinuationLines(text: string): string {
  return text.replace(/\n/g, '\n  ')
}

/** Produce deterministic Unicode text; callers may encode it directly as UTF-8. */
export function exportTranscriptText(
  turns: readonly TranscriptExportTurn[],
  options: TranscriptExportOptions = {},
): string {
  const title = normalizeText(options.title ?? '单人同传记录')
  const includeTurnNumbers = options.includeTurnNumbers ?? true
  const sortedTurns = [...turns].sort((left, right) => left.id - right.id)
  const sections = sortedTurns.map((turn, index) => {
    const sourceText = indentContinuationLines(normalizeText(turn.sourceText))
    const translatedText = indentContinuationLines(normalizeText(turn.translatedText))
    const number = includeTurnNumbers ? `第 ${index + 1} 轮 · ` : ''
    return [
      `${number}${languageLabel[turn.sourceLanguage]} → ${languageLabel[turn.targetLanguage]}`,
      `原文：${sourceText}`,
      `译文：${translatedText}`,
    ].join('\n')
  })

  return [title, ...sections].join('\n\n') + '\n'
}

export const transcriptToText = exportTranscriptText
