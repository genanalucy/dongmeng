import type {
  Ear,
  LanguageCode,
  Side,
  TranslationPort,
  TranslationResult,
  TranslationSession,
  TranslationSessionEvent,
} from '../translation/TranslationPort'

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
    ? { sourceLanguage: leftLanguage, targetLanguage: rightLanguage, speakerEar: 'left', listenerEar: 'right' }
    : { sourceLanguage: rightLanguage, targetLanguage: leftLanguage, speakerEar: 'right', listenerEar: 'left' }
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
  private externalError = false
  private readonly listeners = new Set<Listener>()
  private turnCounter = 0
  private turnGeneration = 0
  private activeSession: TranslationSession | null = null
  private unsubscribeSession: (() => void) | null = null

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
    const route = routeForSide(side, this.leftLanguage, this.rightLanguage)
    const session = this.translationPort.start({
      sourceLanguage: route.sourceLanguage,
      targetLanguage: route.targetLanguage,
      targetEar: route.listenerEar,
    })
    this.activeSession = session
    this.unsubscribeSession = session.subscribe((event) => this.handleSessionEvent(event, side, route))
    this.activeSide = side
    this.state = speakingState[side]
    this.errorMessage = null
    this.externalError = false
    this.emit()
    return true
  }

  public pushAudio(packet: Parameters<TranslationSession['pushAudio']>[0]): void {
    this.activeSession?.pushAudio(packet)
  }

  public async stopSpeaking(fallbackSourceText: string): Promise<void> {
    const side = this.activeSide
    const session = this.activeSession
    if (side === null || session === null || this.state !== speakingState[side]) {
      return
    }
    this.state = translatingState[side]
    const generation = ++this.turnGeneration
    this.emit()
    session.finish(fallbackSourceText)
    try {
      const result = await session.done
      if (!this.isCurrentTurn(generation, side)) {
        return
      }
      this.addSubtitle(side, routeForSide(side, this.leftLanguage, this.rightLanguage), result)
    } catch (error: unknown) {
      if (!this.isCurrentTurn(generation, side)) {
        return
      }
      this.failActiveTurn(error instanceof Error ? error.message : '翻译发生未知错误。')
    }
  }

  public completePlayback(): void {
    if (this.state !== 'left_translating' && this.state !== 'right_translating') {
      return
    }
    this.clearSession()
    this.state = 'ready'
    this.activeSide = null
    this.emit()
  }

  public cancelActiveTurn(): void {
    this.turnGeneration += 1
    this.clearSession(true)
    if (this.activeSide === null) {
      return
    }
    this.state = 'ready'
    this.activeSide = null
    this.emit()
  }

  public reportExternalError(message: string): void {
    this.turnGeneration += 1
    this.clearSession(true)
    this.state = 'error'
    this.activeSide = null
    this.errorMessage = message
    this.externalError = true
    this.emit()
  }

  public recoverFromExternalError(): boolean {
    if (this.state !== 'error' || this.activeSide !== null || !this.externalError) {
      return false
    }
    this.turnGeneration += 1
    this.state = 'ready'
    this.errorMessage = null
    this.externalError = false
    this.emit()
    return true
  }

  public swapLanguages(): boolean {
    if (this.state !== 'ready') {
      return false
    }
    ;[this.leftLanguage, this.rightLanguage] = [this.rightLanguage, this.leftLanguage]
    this.emit()
    return true
  }

  private handleSessionEvent(event: TranslationSessionEvent, side: Side, route: TurnRoute): void {
    if (side !== this.activeSide) {
      return
    }
    if (event.type === 'error') {
      this.turnGeneration += 1
      this.failActiveTurn(event.message)
      return
    }
    if (event.type === 'translation_final') {
      // The final subtitle remains owned by `session.done`, preserving a single terminal boundary.
      return
    }
    if (event.type === 'tts_audio') {
      // TTS bytes are consumed by the injected client sink; playback is intentionally out of scope.
      return
    }
    void route
  }

  private isCurrentTurn(generation: number, side: Side): boolean {
    return this.turnGeneration === generation
      && this.activeSide === side
      && this.state === translatingState[side]
  }

  private addSubtitle(side: Side, route: TurnRoute, result: TranslationResult): void {
    this.turnCounter += 1
    this.subtitles.push({ id: this.turnCounter, side, ...route, sourceText: result.sourceText, translatedText: result.translatedText })
    this.emit()
  }

  private failActiveTurn(message: string): void {
    this.clearSession()
    this.state = 'error'
    this.activeSide = null
    this.errorMessage = message
    this.externalError = false
    this.emit()
  }

  private clearSession(cancel = false): void {
    const session = this.activeSession
    this.activeSession = null
    this.unsubscribeSession?.()
    this.unsubscribeSession = null
    if (cancel) {
      session?.cancel()
    }
  }

  private emit(): void {
    const snapshot = this.getSnapshot()
    this.listeners.forEach((listener) => listener(snapshot))
  }
}
