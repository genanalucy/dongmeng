import type { PcmPacket } from '../audio/PcmCapturePipeline'

export type LanguageCode = 'zh' | 'en'
export type Ear = 'left' | 'right'
export type TranslationPlaybackTarget = Ear | 'both' | 'captions'
export type Side = 'left' | 'right'

export interface TranslationRequest {
  readonly sourceLanguage: LanguageCode
  readonly targetLanguage: LanguageCode
  readonly targetEar: TranslationPlaybackTarget
}

export interface TranslationResult {
  readonly sourceText: string
  readonly translatedText: string
}

export type TranslationSessionEvent =
  | { readonly type: 'ready' }
  | { readonly type: 'source_partial'; readonly text: string }
  | { readonly type: 'source_final'; readonly text: string }
  | { readonly type: 'translation_partial'; readonly text: string }
  | { readonly type: 'translation_final'; readonly text: string }
  | { readonly type: 'tts_audio'; readonly pcm: ArrayBuffer }
  | { readonly type: 'finished' }
  | { readonly type: 'error'; readonly code: string; readonly message: string; readonly preservePlayback?: boolean }

/** A single AST turn. Instances must never be shared by two PTT turns. */
export interface TranslationSession {
  subscribe(listener: (event: TranslationSessionEvent) => void): () => void
  pushAudio(packet: PcmPacket): void
  finish(fallbackSourceText: string): void
  cancel(): void
  readonly done: Promise<TranslationResult>
}

/** Injectable transport boundary. Every `start` creates an independent session. */
export interface TranslationPort {
  start(request: TranslationRequest): TranslationSession
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

/** Deterministic UI-only implementation; useful only when the user explicitly selects mock mode. */
export class DeterministicMockTranslationPort implements TranslationPort {
  public start(request: TranslationRequest): TranslationSession {
    return new DeterministicMockTranslationSession(request)
  }
}

class DeterministicMockTranslationSession implements TranslationSession {
  private readonly listeners = new Set<(event: TranslationSessionEvent) => void>()
  private settled = false
  private resolveDone: ((result: TranslationResult) => void) | null = null
  public readonly done: Promise<TranslationResult>

  public constructor(private readonly request: TranslationRequest) {
    this.done = new Promise<TranslationResult>((resolve) => {
      this.resolveDone = resolve
    })
    queueMicrotask(() => this.emit({ type: 'ready' }))
  }

  public subscribe(listener: (event: TranslationSessionEvent) => void): () => void {
    this.listeners.add(listener)
    return () => this.listeners.delete(listener)
  }

  public pushAudio(): void {
    // Mock mode intentionally observes no microphone data.
  }

  public finish(fallbackSourceText: string): void {
    if (this.settled) {
      return
    }
    this.settled = true
    const translatedText = translations[this.request.sourceLanguage][fallbackSourceText]
      ?? fallbackTexts[this.request.targetLanguage]
    const result = { sourceText: fallbackSourceText, translatedText }
    queueMicrotask(() => {
      this.emit({ type: 'source_final', text: result.sourceText })
      this.emit({ type: 'translation_final', text: result.translatedText })
      this.emit({ type: 'finished' })
      this.resolveDone?.(result)
      this.resolveDone = null
    })
  }

  public cancel(): void {
    this.settled = true
  }

  private emit(event: TranslationSessionEvent): void {
    this.listeners.forEach((listener) => listener(event))
  }
}
