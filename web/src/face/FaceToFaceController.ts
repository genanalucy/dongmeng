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
type AudioPacket = Parameters<TranslationSession['pushAudio']>[0]

interface BackgroundTurn {
  readonly id: number
  readonly side: Side
  readonly route: TurnRoute
  readonly session: TranslationSession
  subtitleId: number | null
  unsubscribe: () => void
  capturing: boolean
}

export interface PlaybackIdlePort {
  readonly isIdle: boolean
  whenIdle(): Promise<void>
  clear(): void
}

const immediateIdlePlayback: PlaybackIdlePort = {
  isIdle: true,
  whenIdle: async () => undefined,
  clear: () => undefined,
}

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
  private activeSubtitleID: number | null = null
  private readonly backgroundTurns = new Map<number, BackgroundTurn>()
  private capturingBackgroundTurnId: number | null = null

  public constructor(
    private readonly translationPort: TranslationPort,
    private readonly playback: PlaybackIdlePort = immediateIdlePlayback,
  ) {}

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
      subtitles: [...this.subtitles].sort((left, right) => left.id - right.id),
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
    this.activeSubtitleID = null
    this.unsubscribeSession = session.subscribe((event) => this.handleSessionEvent(event, side, route))
    this.activeSide = side
    this.state = speakingState[side]
    this.errorMessage = null
    this.externalError = false
    this.emit()
    return true
  }

  public pushAudio(packet: AudioPacket): void {
    this.activeSession?.pushAudio(packet)
  }

  public startBackgroundTurn(side: Side): number | null {
    if (!this.canStart(side) || this.capturingBackgroundTurnId !== null) {
      return null
    }
    const route = routeForSide(side, this.leftLanguage, this.rightLanguage)
    const session = this.translationPort.start({
      sourceLanguage: route.sourceLanguage,
      targetLanguage: route.targetLanguage,
      targetEar: route.listenerEar,
    })
    this.turnCounter += 1
    const turn: BackgroundTurn = {
      id: this.turnCounter,
      side,
      route,
      session,
      subtitleId: this.turnCounter,
      unsubscribe: () => undefined,
      capturing: true,
    }
    turn.unsubscribe = session.subscribe((event) => this.handleBackgroundSessionEvent(turn.id, event))
    this.backgroundTurns.set(turn.id, turn)
    this.capturingBackgroundTurnId = turn.id
    this.activeSide = side
    this.state = speakingState[side]
    this.errorMessage = null
    this.externalError = false
    this.emit()
    return turn.id
  }

  public pushAudioForTurn(turnId: number, packet: AudioPacket): void {
    const turn = this.backgroundTurns.get(turnId)
    if (turn?.capturing === true) {
      turn.session.pushAudio(packet)
    }
  }

  public finishBackgroundTurn(turnId: number, fallbackSourceText: string): void {
    const turn = this.backgroundTurns.get(turnId)
    if (turn?.capturing !== true) {
      return
    }
    turn.capturing = false
    turn.session.finish(fallbackSourceText)
    if (this.capturingBackgroundTurnId === turnId) {
      this.capturingBackgroundTurnId = null
      this.activeSide = null
      this.state = 'ready'
      this.emit()
    }
    void this.waitForBackgroundTurn(turn)
  }

  public cancelAllTurns(): void {
    this.turnGeneration += 1
    this.playback.clear()
    this.discardActiveSubtitle()
    this.clearSession(true)
    for (const turn of this.backgroundTurns.values()) {
      turn.unsubscribe()
      turn.session.cancel()
      this.discardSubtitle(turn.subtitleId)
    }
    this.backgroundTurns.clear()
    this.capturingBackgroundTurnId = null
    this.state = 'ready'
    this.activeSide = null
    this.errorMessage = null
    this.externalError = false
    this.emit()
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
      this.upsertSubtitle(side, routeForSide(side, this.leftLanguage, this.rightLanguage), result)
      await this.playback.whenIdle()
      if (!this.isCurrentTurn(generation, side) || !this.playback.isIdle) {
        return
      }
      this.clearSession()
      this.state = 'ready'
      this.activeSide = null
      this.emit()
    } catch (error: unknown) {
      if (!this.isCurrentTurn(generation, side)) {
        return
      }
      this.failActiveTurn(error instanceof Error ? error.message : '翻译发生未知错误。')
    }
  }

  public cancelActiveTurn(): void {
    this.turnGeneration += 1
    this.playback.clear()
    this.discardActiveSubtitle()
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
    this.playback.clear()
    this.discardActiveSubtitle()
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

  private handleBackgroundSessionEvent(turnId: number, event: TranslationSessionEvent): void {
    const turn = this.backgroundTurns.get(turnId)
    if (turn === undefined) {
      return
    }
    if (event.type === 'error') {
      this.removeBackgroundTurn(turnId)
      if (this.capturingBackgroundTurnId === turnId) {
        this.capturingBackgroundTurnId = null
        this.activeSide = null
        this.state = 'ready'
        this.emit()
      }
      return
    }
    if (event.type === 'source_partial' || event.type === 'source_final') {
      const subtitle = this.subtitleById(turn.subtitleId)
      turn.subtitleId = this.upsertSubtitleForId(turn.subtitleId, turn.side, turn.route, {
        sourceText: event.text,
        translatedText: subtitle?.translatedText ?? '',
      })
      return
    }
    if (event.type === 'translation_partial' || event.type === 'translation_final') {
      const subtitle = this.subtitleById(turn.subtitleId)
      turn.subtitleId = this.upsertSubtitleForId(turn.subtitleId, turn.side, turn.route, {
        sourceText: subtitle?.sourceText ?? '',
        translatedText: event.text,
      })
    }
  }

  private async waitForBackgroundTurn(turn: BackgroundTurn): Promise<void> {
    try {
      const result = await turn.session.done
      if (this.backgroundTurns.get(turn.id) !== turn) {
        return
      }
      turn.subtitleId = this.upsertSubtitleForId(turn.subtitleId, turn.side, turn.route, result)
      this.removeBackgroundTurn(turn.id)
    } catch {
      if (this.backgroundTurns.get(turn.id) === turn) {
        this.removeBackgroundTurn(turn.id)
      }
    }
  }

  private removeBackgroundTurn(turnId: number): void {
    const turn = this.backgroundTurns.get(turnId)
    if (turn === undefined) {
      return
    }
    turn.unsubscribe()
    this.backgroundTurns.delete(turnId)
  }

  private handleSessionEvent(event: TranslationSessionEvent, side: Side, route: TurnRoute): void {
    if (side !== this.activeSide) {
      return
    }
    if (event.type === 'error') {
      this.turnGeneration += 1
      this.failActiveTurn(event.message, event.preservePlayback === true)
      return
    }
    if (event.type === 'source_partial' || event.type === 'source_final') {
      this.upsertSubtitle(side, route, { sourceText: event.text, translatedText: this.activeSubtitle()?.translatedText ?? '' })
      return
    }
    if (event.type === 'translation_partial' || event.type === 'translation_final') {
      this.upsertSubtitle(side, route, { sourceText: this.activeSubtitle()?.sourceText ?? '', translatedText: event.text })
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

  private upsertSubtitle(side: Side, route: TurnRoute, result: TranslationResult): void {
    this.activeSubtitleID = this.upsertSubtitleForId(this.activeSubtitleID, side, route, result)
  }

  private upsertSubtitleForId(
    subtitleId: number | null,
    side: Side,
    route: TurnRoute,
    result: TranslationResult,
  ): number {
    const existingIndex = subtitleId === null
      ? -1
      : this.subtitles.findIndex((subtitle) => subtitle.id === subtitleId)
    if (existingIndex >= 0) {
      this.subtitles[existingIndex] = {
        ...this.subtitles[existingIndex],
        sourceText: result.sourceText,
        translatedText: result.translatedText,
      }
      this.emit()
      return this.subtitles[existingIndex].id
    }
    const id = subtitleId ?? ++this.turnCounter
    this.subtitles.push({ id, side, ...route, sourceText: result.sourceText, translatedText: result.translatedText })
    this.emit()
    return id
  }

  private subtitleById(subtitleId: number | null): SubtitleTurn | null {
    if (subtitleId === null) {
      return null
    }
    return this.subtitles.find((subtitle) => subtitle.id === subtitleId) ?? null
  }

  private activeSubtitle(): SubtitleTurn | null {
    return this.subtitleById(this.activeSubtitleID)
  }

  private discardSubtitle(subtitleId: number | null): void {
    if (subtitleId === null) {
      return
    }
    const index = this.subtitles.findIndex((subtitle) => subtitle.id === subtitleId)
    if (index >= 0) {
      this.subtitles.splice(index, 1)
    }
  }

  private discardActiveSubtitle(): void {
    this.discardSubtitle(this.activeSubtitleID)
    this.activeSubtitleID = null
  }

  private failActiveTurn(message: string, preservePlayback = false): void {
    if (!preservePlayback) {
      this.playback.clear()
    }
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
    this.activeSubtitleID = null
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
