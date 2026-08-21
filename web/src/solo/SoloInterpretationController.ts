import type { PcmPacket } from '../audio/PcmCapturePipeline'
import type {
  LanguageCode,
  TranslationPort,
  TranslationResult,
  TranslationSession,
  TranslationSessionEvent,
} from '../translation/TranslationPort'

export type SoloInterpretationState = 'idle' | 'capturing' | 'paused' | 'stopping' | 'error'
export type SoloTarget = 'both' | 'left' | 'right' | 'captions'

export interface SoloTranscriptTurn {
  readonly id: number
  readonly sourceLanguage: LanguageCode
  readonly targetLanguage: LanguageCode
  readonly target: SoloTarget
  readonly sourceText: string
  readonly translatedText: string
  readonly status: 'capturing' | 'stopping' | 'finished' | 'error'
  readonly error: string | null
}

export interface SoloInterpretationSnapshot {
  readonly sourceLanguage: LanguageCode
  readonly targetLanguage: LanguageCode
  readonly target: SoloTarget
  readonly state: SoloInterpretationState
  readonly activeTurnId: number | null
  readonly turns: readonly SoloTranscriptTurn[]
  readonly error: string | null
}

export interface SoloInterpretationOptions {
  readonly sourceLanguage?: LanguageCode
  readonly targetLanguage?: LanguageCode
  readonly target?: SoloTarget
}

type Listener = (snapshot: SoloInterpretationSnapshot) => void

interface ManagedTurn {
  readonly id: number
  readonly session: TranslationSession
  unsubscribe: () => void
  capturing: boolean
  visible: boolean
}

export class SoloInterpretationController {
  private sourceLanguage: LanguageCode
  private targetLanguage: LanguageCode
  private target: SoloTarget
  private state: SoloInterpretationState = 'idle'
  private activeTurnId: number | null = null
  private readonly transcriptTurns: SoloTranscriptTurn[] = []
  private error: string | null = null
  private turnCounter = 0
  private readonly managedTurns = new Map<number, ManagedTurn>()
  private readonly listeners = new Set<Listener>()

  public constructor(
    private readonly translationPort: TranslationPort,
    options: SoloInterpretationOptions = {},
  ) {
    this.sourceLanguage = options.sourceLanguage ?? 'zh'
    this.targetLanguage = options.targetLanguage ?? 'en'
    this.target = options.target ?? 'both'
    if (this.sourceLanguage === this.targetLanguage) {
      throw new Error('源语言和目标语言不能相同。')
    }
  }

  public subscribe(listener: Listener): () => void {
    this.listeners.add(listener)
    listener(this.getSnapshot())
    return () => this.listeners.delete(listener)
  }

  public getSnapshot(): SoloInterpretationSnapshot {
    return {
      sourceLanguage: this.sourceLanguage,
      targetLanguage: this.targetLanguage,
      target: this.target,
      state: this.state,
      activeTurnId: this.activeTurnId,
      turns: [...this.transcriptTurns].sort((left, right) => left.id - right.id),
      error: this.error,
    }
  }

  public setTarget(target: SoloTarget): boolean {
    if (this.state !== 'idle' && this.state !== 'paused') {
      return false
    }
    this.target = target
    this.emit()
    return true
  }

  public swapLanguages(): boolean {
    if (this.state !== 'idle' && this.state !== 'paused') {
      return false
    }
    ;[this.sourceLanguage, this.targetLanguage] = [this.targetLanguage, this.sourceLanguage]
    this.emit()
    return true
  }

  public setLanguages(sourceLanguage: LanguageCode, targetLanguage: LanguageCode): boolean {
    if ((this.state !== 'idle' && this.state !== 'paused') || sourceLanguage === targetLanguage) {
      return false
    }
    this.sourceLanguage = sourceLanguage
    this.targetLanguage = targetLanguage
    this.emit()
    return true
  }

  public startTurn(): number | null {
    if (this.activeTurnId !== null || (this.state !== 'idle' && this.state !== 'stopping')) {
      return null
    }

    const session = this.translationPort.start({
      sourceLanguage: this.sourceLanguage,
      targetLanguage: this.targetLanguage,
      targetEar: this.target,
    })
    const id = ++this.turnCounter
    const managed: ManagedTurn = {
      id,
      session,
      unsubscribe: () => undefined,
      capturing: true,
      visible: true,
    }
    managed.unsubscribe = session.subscribe((event) => this.handleSessionEvent(id, event))
    this.managedTurns.set(id, managed)
    this.transcriptTurns.push({
      id,
      sourceLanguage: this.sourceLanguage,
      targetLanguage: this.targetLanguage,
      target: this.target,
      sourceText: '',
      translatedText: '',
      status: 'capturing',
      error: null,
    })
    this.activeTurnId = id
    this.state = 'capturing'
    this.error = null
    this.emit()
    return id
  }

  public pushAudio(turnId: number, packet: PcmPacket): void {
    const turn = this.managedTurns.get(turnId)
    if (turn?.capturing === true && this.activeTurnId === turnId && this.state === 'capturing') {
      turn.session.pushAudio(packet)
    }
  }

  public finishTurn(turnId: number, fallbackSourceText: string): void {
    const turn = this.managedTurns.get(turnId)
    if (turn?.capturing !== true || this.activeTurnId !== turnId) {
      return
    }
    turn.capturing = false
    this.updateTranscript(turnId, { status: 'stopping' })
    this.activeTurnId = null
    this.state = 'stopping'
    turn.session.finish(fallbackSourceText)
    this.emit()
    void this.settleTurn(turn)
  }

  public pause(): boolean {
    if (this.state !== 'idle' && this.state !== 'capturing' && this.state !== 'stopping') {
      return false
    }
    this.state = 'paused'
    this.emit()
    return true
  }

  /** Resume capture state only; microphone lifecycle remains page-owned. */
  public resume(): boolean {
    if (this.state !== 'paused') {
      return false
    }
    this.state = this.activeTurnId === null
      ? (this.managedTurns.size === 0 ? 'idle' : 'stopping')
      : 'capturing'
    this.emit()
    return true
  }

  public async finishAll(fallbackSourceText: string): Promise<void> {
    const activeTurnId = this.activeTurnId
    if (activeTurnId !== null) {
      this.finishTurn(activeTurnId, fallbackSourceText)
    }
    if (this.managedTurns.size === 0) {
      if (this.state !== 'error' && this.state !== 'paused') {
        this.state = 'idle'
        this.emit()
      }
      return
    }
    if (this.state !== 'paused') {
      this.state = 'stopping'
      this.emit()
    }
    await Promise.allSettled([...this.managedTurns.values()].map((turn) => turn.session.done))
  }

  public cancelAll(): void {
    for (const turn of this.managedTurns.values()) {
      turn.unsubscribe()
      turn.session.cancel()
      if (this.transcriptById(turn.id)?.status !== 'finished') {
        this.removeTranscript(turn.id)
      }
    }
    this.managedTurns.clear()
    this.activeTurnId = null
    this.state = 'idle'
    this.error = null
    this.emit()
  }

  public clearTranscript(): void {
    this.transcriptTurns.length = 0
    for (const turn of this.managedTurns.values()) {
      turn.visible = false
    }
    this.emit()
  }

  private handleSessionEvent(turnId: number, event: TranslationSessionEvent): void {
    const managed = this.managedTurns.get(turnId)
    if (managed === undefined) {
      return
    }
    if (event.type === 'source_partial' || event.type === 'source_final') {
      this.updateTranscript(turnId, { sourceText: event.text })
      return
    }
    if (event.type === 'translation_partial' || event.type === 'translation_final') {
      this.updateTranscript(turnId, { translatedText: event.text })
      return
    }
    if (event.type === 'error') {
      this.failTurn(managed, event.message)
    }
  }

  private async settleTurn(turn: ManagedTurn): Promise<void> {
    try {
      const result = await turn.session.done
      if (this.managedTurns.get(turn.id) !== turn) {
        return
      }
      this.updateTranscript(turn.id, {
        sourceText: result.sourceText,
        translatedText: result.translatedText,
        status: 'finished',
        error: null,
      })
      this.removeManagedTurn(turn)
      this.restoreNonCapturingState()
    } catch (caught: unknown) {
      if (this.managedTurns.get(turn.id) === turn) {
        this.failTurn(turn, caught instanceof Error ? caught.message : '翻译发生未知错误。')
      }
    }
  }

  private failTurn(turn: ManagedTurn, message: string): void {
    const wasCurrent = this.activeTurnId === turn.id
    this.updateTranscript(turn.id, { status: 'error', error: message })
    this.removeManagedTurn(turn)
    if (wasCurrent) {
      this.activeTurnId = null
      this.state = 'error'
      this.error = message
      this.emit()
      return
    }
    this.restoreNonCapturingState()
  }

  private restoreNonCapturingState(): void {
    if (this.activeTurnId !== null || this.state === 'paused' || this.state === 'error') {
      return
    }
    this.state = this.managedTurns.size === 0 ? 'idle' : 'stopping'
    this.emit()
  }

  private removeManagedTurn(turn: ManagedTurn): void {
    turn.unsubscribe()
    this.managedTurns.delete(turn.id)
  }

  private transcriptById(turnId: number): SoloTranscriptTurn | null {
    return this.transcriptTurns.find((turn) => turn.id === turnId) ?? null
  }

  private updateTranscript(
    turnId: number,
    update: Partial<Pick<SoloTranscriptTurn, 'sourceText' | 'translatedText' | 'status' | 'error'>>,
  ): void {
    const managed = this.managedTurns.get(turnId)
    if (managed?.visible !== true) {
      return
    }
    const index = this.transcriptTurns.findIndex((turn) => turn.id === turnId)
    if (index < 0) {
      return
    }
    this.transcriptTurns[index] = { ...this.transcriptTurns[index], ...update }
    this.emit()
  }

  private removeTranscript(turnId: number): void {
    const index = this.transcriptTurns.findIndex((turn) => turn.id === turnId)
    if (index >= 0) {
      this.transcriptTurns.splice(index, 1)
    }
  }

  private emit(): void {
    const snapshot = this.getSnapshot()
    this.listeners.forEach((listener) => listener(snapshot))
  }
}

export type SoloAudioPacket = Parameters<TranslationSession['pushAudio']>[0]
export type SoloTranslationResult = TranslationResult
