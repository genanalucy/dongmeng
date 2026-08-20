import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import type { SubtitleTurn } from '../face/FaceToFaceController'
import { SubtitlePanel } from './SubtitlePanel'
import { isNearScrollBottom } from './useFollowLatest'

const firstTurn: SubtitleTurn = {
  id: 1,
  side: 'left',
  sourceLanguage: 'zh',
  targetLanguage: 'en',
  speakerEar: 'left',
  listenerEar: 'right',
  sourceText: '你好',
  translatedText: 'Hello',
}

const secondTurn: SubtitleTurn = {
  ...firstTurn,
  id: 2,
  side: 'right',
  sourceLanguage: 'en',
  targetLanguage: 'zh',
  speakerEar: 'right',
  listenerEar: 'left',
  sourceText: 'How are you?',
  translatedText: '你好吗？',
}

function setScrollMetrics(element: HTMLElement, values: { scrollTop: number; clientHeight: number; scrollHeight: number }): void {
  Object.defineProperties(element, {
    scrollTop: { configurable: true, writable: true, value: values.scrollTop },
    clientHeight: { configurable: true, value: values.clientHeight },
    scrollHeight: { configurable: true, value: values.scrollHeight },
  })
}

describe('SubtitlePanel', () => {
  it('calculates the near-bottom threshold without depending on the DOM', () => {
    expect(isNearScrollBottom(376, 600, 1_000)).toBe(true)
    expect(isNearScrollBottom(300, 600, 1_000)).toBe(false)
  })

  it('does not steal scroll and counts messages while the reader is away from the bottom', async () => {
    const { rerender } = render(<SubtitlePanel subtitles={[firstTurn]} simulated />)
    const stream = screen.getByRole('list', { name: '实时字幕消息' })
    setScrollMetrics(stream, { scrollTop: 100, clientHeight: 300, scrollHeight: 800 })
    fireEvent.scroll(stream)

    expect(screen.getByRole('button', { name: /回到最新/ })).toBeInTheDocument()
    rerender(<SubtitlePanel subtitles={[firstTurn, secondTurn]} simulated />)

    expect(await screen.findByRole('button', { name: /1 条新消息/ })).toBeInTheDocument()
    await waitFor(() => expect(stream.scrollTop).toBe(100))
  })

  it('returns to the latest message by keyboard-accessible button', () => {
    render(<SubtitlePanel subtitles={[firstTurn, secondTurn]} simulated />)
    const stream = screen.getByRole('list', { name: '实时字幕消息' })
    setScrollMetrics(stream, { scrollTop: 10, clientHeight: 300, scrollHeight: 800 })
    fireEvent.scroll(stream)

    fireEvent.click(screen.getByRole('button', { name: /回到最新/ }))

    expect(stream.scrollTop).toBe(800)
    expect(screen.queryByRole('button', { name: /回到最新/ })).not.toBeInTheDocument()
  })

  it('resets follow state when the stream is cleared and leaves no deferred update after unmount', async () => {
    const { rerender, unmount } = render(<SubtitlePanel subtitles={[firstTurn]} simulated />)
    const stream = screen.getByRole('list', { name: '实时字幕消息' })
    setScrollMetrics(stream, { scrollTop: 0, clientHeight: 200, scrollHeight: 800 })
    fireEvent.scroll(stream)
    rerender(<SubtitlePanel subtitles={[firstTurn, secondTurn]} simulated />)
    rerender(<SubtitlePanel subtitles={[]} simulated />)

    expect(await screen.findByText(/按住任一侧 PTT/)).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /新消息|回到最新/ })).not.toBeInTheDocument()
    expect(() => unmount()).not.toThrow()
    await Promise.resolve()
  })
})
