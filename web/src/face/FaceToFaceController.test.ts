import { describe, expect, it } from 'vitest'
import { FaceToFaceController, routeForSide } from './FaceToFaceController'
import type { TranslationPort } from '../translation/TranslationPort'

const port: TranslationPort = {
  translate: async (request) => ({
    sourceText: request.sourceText,
    translatedText: `${request.sourceLanguage}->${request.targetLanguage}`,
    playbackDurationMs: 1,
  }),
}

describe('FaceToFaceController', () => {
  it('maps each speaker to the opposite listening ear and keeps speaker ear silent by contract', () => {
    expect(routeForSide('left', 'zh', 'en')).toEqual({
      sourceLanguage: 'zh',
      targetLanguage: 'en',
      speakerEar: 'left',
      listenerEar: 'right',
    })
    expect(routeForSide('right', 'zh', 'en')).toEqual({
      sourceLanguage: 'en',
      targetLanguage: 'zh',
      speakerEar: 'right',
      listenerEar: 'left',
    })
  })

  it('enforces half duplex through speaking, translating, and ready', async () => {
    const controller = new FaceToFaceController(port)

    expect(controller.startSpeaking('left')).toBe(true)
    expect(controller.getSnapshot().state).toBe('left_speaking')
    expect(controller.startSpeaking('right')).toBe(false)

    await controller.stopSpeaking('你好，我叫李明。')
    expect(controller.getSnapshot().state).toBe('left_translating')
    expect(controller.startSpeaking('right')).toBe(false)
    expect(controller.getSnapshot().subtitles[0]).toMatchObject({
      sourceLanguage: 'zh',
      targetLanguage: 'en',
      listenerEar: 'right',
    })

    controller.completePlayback()
    expect(controller.getSnapshot().state).toBe('ready')
    expect(controller.startSpeaking('right')).toBe(true)
  })

  it('swaps only languages while preserving physical ears', () => {
    const controller = new FaceToFaceController(port)

    expect(controller.swapLanguages()).toBe(true)
    expect(controller.getSnapshot()).toMatchObject({ leftLanguage: 'en', rightLanguage: 'zh' })
    expect(routeForSide('left', 'en', 'zh').listenerEar).toBe('right')
  })

  it('returns to ready when an active turn is cancelled', () => {
    const controller = new FaceToFaceController(port)
    controller.startSpeaking('right')

    controller.cancelActiveTurn()

    expect(controller.getSnapshot()).toMatchObject({ state: 'ready', activeSide: null })
  })
})
