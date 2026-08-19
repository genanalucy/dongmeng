import type { Ear, LanguageCode, Side, TranslationPort, TranslationResult } from '../translation/TranslationPort'

export type FaceToFaceState =
  | 'ready'
  | 'left_speaking'
  | 'left_translating'
  | 'right_speaking'
  | 'right_translating'
  | 'error'

export interface TurnRoute {
  readonly sourceLanguage: LanguageCode
  readonly targetLanguage: LanguageCode
  readonly speakerEar: Ear
  readonly listenerEar: Ear
}

export interface SubtitleTurn extends TurnRoute {
  readonly id: number
  readonly side: Side
  readonly sourceText: string
  readonly translatedText: string
}

export interface FaceToFaceSnapshot {
  readonly state: FaceToFaceState
  readonly leftLanguage: LanguageCode
  readonly rightLanguage: LanguageCode
  readonly activeSide: Side | null
  readonly subtitles: readonly SubtitleTurn[]
  readonly errorMessage: string | null
}

type Listener = (snapshot: FaceToFaceSnapshot) => void

export function routeForSide(
  side: Side,
  leftLanguage: LanguageCode,
  rightLanguage: LanguageCode,
): TurnRoute {
  return side === 'left'
    ? {
        sourceLanguage: leftLanguage,
        targetLanguage: rightLanguage,
        speakerEar: 'left',
        listenerEar: 'right',
      }
    : {
        sourceLanguage: rightLanguage,
        targetLanguage: leftLanguage,
        speakerEar: 'right',
        listenerEar: 'left',
      }
}

const speakingState: Readonly<Record<Side, FaceToFaceState>> = {
  left: 'left_speaking',
  right: 'right_speaking',
}

const translatingState: Readonly<Record<Side, FaceToFaceState>> = {
  left: 'left_translating',
  right: 'right_translating',
}

export class FaceToFaceController {
  private state: FaceToFaceState = 'ready'
  private leftLanguage: LanguageCode = 'zh'
  private rightLanguage: LanguageCode = 'en'
  private activeSide: Side | null = null
  private readonly subtitles: SubtitleTurn[] = []
  private errorMessage: string | null = null
  private readonly listeners = new Set<Listener>()
  private turnCounter = 0

  public constructor(private readonly translationPort: TranslationPort) {}

  public subscribe(listener: Listener): () => void {
    this.listeners.add(listener)
    listener(this.getSnapshot())
    return () => this.listeners.delete(listener)
  }

  public getSnapshot(): FaceToFaceSnapshot {
    return {
      state: this.state,
      leftLanguage: this.leftLanguage,
      rightLanguage: this.rightLanguage,
      activeSide: this.activeSide,
      subtitles: [...this.subtitles],
      errorMessage: this.errorMessage,
    }
  }

  public canStart(side: Side): boolean {
    return this.state === 'ready' && this.activeSide === null && side !== this.activeSide
  }

  public startSpeaking(side: Side): boolean {
    if (!this.canStart(side)) {
      return false
    }

    this.activeSide = side
    this.state = speakingState[side]
    this.errorMessage = null
    this.emit()
    return true
  }

  public async stopSpeaking(sourceText: string): Promise<void> {
    const side = this.activeSide
    if (side === null || this.state !== speakingState[side]) {
      return
    }

    this.state = translatingState[side]
    this.emit()

    try {
      const route = routeForSide(side, this.leftLanguage, this.rightLanguage)
      const result = await this.translationPort.translate({
        sourceLanguage: route.sourceLanguage,
        targetLanguage: route.targetLanguage,
        sourceText,
      })
      this.addSubtitle(side, route, result)
    } catch (error: unknown) {
      this.state = 'error'
      this.activeSide = null
      this.errorMessage = error instanceof Error ? error.message : '模拟翻译发生未知错误。'
      this.emit()
    }
  }

  public completePlayback(): void {
    if (this.state !== 'left_translating' && this.state !== 'right_translating') {
      return
    }

    this.state = 'ready'
    this.activeSide = null
    this.emit()
  }

  public cancelActiveTurn(): void {
    if (this.activeSide === null) {
      return
    }

    this.state = 'ready'
    this.activeSide = null
    this.emit()
  }

  public reportExternalError(message: string): void {
    this.state = 'error'
    this.activeSide = null
    this.errorMessage = message
    this.emit()
  }

  public swapLanguages(): boolean {
    if (this.state !== 'ready') {
      return false
    }

    ;[this.leftLanguage, this.rightLanguage] = [this.rightLanguage, this.leftLanguage]
    this.emit()
    return true
  }

  private addSubtitle(side: Side, route: TurnRoute, result: TranslationResult): void {
    this.turnCounter += 1
    this.subtitles.push({
      id: this.turnCounter,
      side,
      ...route,
      sourceText: result.sourceText,
      translatedText: result.translatedText,
    })
    this.emit()
  }

  private emit(): void {
    const snapshot = this.getSnapshot()
    this.listeners.forEach((listener) => listener(snapshot))
  }
}
