import { describe, expect, it } from 'vitest'
import { exportTranscriptText } from './transcriptExport'

describe('exportTranscriptText', () => {
  it('formats sorted bilingual turns as deterministic UTF-8-friendly text', () => {
    const text = exportTranscriptText([
      {
        id: 2,
        sourceLanguage: 'en',
        targetLanguage: 'zh',
        sourceText: 'Nice to meet you.',
        translatedText: '很高兴认识你。',
      },
      {
        id: 1,
        sourceLanguage: 'zh',
        targetLanguage: 'en',
        sourceText: '你好，\r\n世界',
        translatedText: 'Hello,\nworld',
      },
    ], { title: '会议同传 🗣️' })

    expect(text).toBe([
      '会议同传 🗣️',
      '',
      '第 1 轮 · 中文 → English',
      '原文：你好，',
      '  世界',
      '译文：Hello,',
      '  world',
      '',
      '第 2 轮 · English → 中文',
      '原文：Nice to meet you.',
      '译文：很高兴认识你。',
      '',
    ].join('\n'))
    expect(new TextDecoder().decode(new TextEncoder().encode(text))).toBe(text)
  })

  it('supports an empty transcript and omitting turn numbers', () => {
    expect(exportTranscriptText([])).toBe('单人同传记录\n')
    expect(exportTranscriptText([{
      id: 9,
      sourceLanguage: 'zh',
      targetLanguage: 'en',
      sourceText: '  原文  ',
      translatedText: ' translation ',
    }], { includeTurnNumbers: false })).toBe([
      '单人同传记录',
      '',
      '中文 → English',
      '原文：原文',
      '译文：translation',
      '',
    ].join('\n'))
  })
})
