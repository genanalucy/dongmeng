export type LanguageCode = 'zh' | 'en'
export type Ear = 'left' | 'right'
export type Side = 'left' | 'right'

export interface TranslationRequest {
  readonly sourceLanguage: LanguageCode
  readonly targetLanguage: LanguageCode
  readonly sourceText: string
}

export interface TranslationResult {
  readonly sourceText: string
  readonly translatedText: string
  readonly playbackDurationMs: number
}

/** Boundary for the future Local Agent / Volcengine translation adapter. */
export interface TranslationPort {
  translate(request: TranslationRequest): Promise<TranslationResult>
}

const translations: Readonly<Record<LanguageCode, Readonly<Record<string, string>>>> = {
  zh: {
    '你好，请问你叫什么名字？': "Hello, what's your name?",
    '你好，我叫李明。': 'Hello, my name is Li Ming.',
    '很高兴认识你。': 'Nice to meet you.',
  },
  en: {
    'Hello, what\'s your name?': '你好，请问你叫什么名字？',
    'Hello, my name is Li Ming.': '你好，我叫李明。',
    'Nice to meet you.': '很高兴认识你。',
    'My name is Jack.': '我叫杰克。',
  },
}

const fallbackTexts: Readonly<Record<LanguageCode, string>> = {
  zh: '这是模拟中文译文。',
  en: 'This is a simulated English translation.',
}

/** Deterministic UI-only implementation. It never contacts a translation service. */
export class DeterministicMockTranslationPort implements TranslationPort {
  async translate(request: TranslationRequest): Promise<TranslationResult> {
    const translatedText = translations[request.sourceLanguage][request.sourceText]
      ?? fallbackTexts[request.targetLanguage]

    return {
      sourceText: request.sourceText,
      translatedText,
      playbackDurationMs: 700,
    }
  }
}
