import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'
import { FaceToFaceController } from '../face/FaceToFaceController'
import { DeterministicMockTranslationPort } from '../translation/TranslationPort'
import { FaceToFacePage } from './FaceToFacePage'

function renderPage(): void {
  render(
    <FaceToFacePage
      controller={new FaceToFaceController(new DeterministicMockTranslationPort())}
      onBack={() => undefined}
    />,
  )
}

describe('FaceToFacePage', () => {
  it('locks the opposite PTT while a participant holds the button and shows a simulated turn', async () => {
    renderPage()
    const leftButton = screen.getByRole('button', { name: /左耳.*按住说话 中文/i })
    const rightButton = screen.getByRole('button', { name: /右耳.*hold to speak english/i })

    fireEvent.pointerDown(leftButton)
    expect(rightButton).toBeDisabled()

    fireEvent.pointerUp(leftButton)
    expect(rightButton).toBeDisabled()
    await screen.findByText(/Hello, my name is Li Ming\./)
    expect(screen.getByText(/播放目标：右耳/)).toBeInTheDocument()

    await waitFor(() => expect(rightButton).toBeEnabled(), { timeout: 1200 })
  })

  it('ends an active PTT turn when the window loses focus', () => {
    renderPage()
    const leftButton = screen.getByRole('button', { name: /左耳.*按住说话 中文/i })
    const rightButton = screen.getByRole('button', { name: /右耳.*hold to speak english/i })

    fireEvent.pointerDown(leftButton)
    expect(rightButton).toBeDisabled()
    fireEvent(window, new Event('blur'))

    expect(rightButton).toBeEnabled()
  })

  it('treats pointercancel like pointerup and runs the simulated translation', async () => {
    renderPage()
    const leftButton = screen.getByRole('button', { name: /左耳.*按住说话 中文/i })
    const rightButton = screen.getByRole('button', { name: /右耳.*hold to speak english/i })

    fireEvent.pointerDown(leftButton)
    expect(rightButton).toBeDisabled()

    fireEvent.pointerCancel(leftButton)

    expect(rightButton).toBeDisabled()
    await screen.findByText(/Hello, my name is Li Ming\./)
    expect(screen.getByText(/播放目标：右耳/)).toBeInTheDocument()
  })

  it('disables language swapping while speaking or translating', async () => {
    renderPage()
    const leftButton = screen.getByRole('button', { name: /左耳.*按住说话 中文/i })
    const swapButton = screen.getByRole('button', { name: '交换左右语言' })

    fireEvent.pointerDown(leftButton)
    expect(swapButton).toBeDisabled()

    fireEvent.pointerUp(leftButton)
    expect(swapButton).toBeDisabled()
    expect(screen.getByText('LEFT · 左耳').parentElement).toHaveTextContent('中文')
    expect(screen.getByText('RIGHT · 右耳').parentElement).toHaveTextContent('English')

    await screen.findByText(/Hello, my name is Li Ming\./)
  })

  it('swaps displayed languages only while ready and marks ear tests', async () => {
    const user = userEvent.setup()
    renderPage()

    await user.click(screen.getByRole('button', { name: '交换左右语言' }))
    expect(screen.getByText('LEFT · 左耳').parentElement).toHaveTextContent('English')
    expect(screen.getByText('RIGHT · 右耳').parentElement).toHaveTextContent('中文')

    await user.click(screen.getByRole('button', { name: '测试左耳' }))
    expect(screen.getByRole('button', { name: /测试左耳/ })).toHaveTextContent('✓ 测试左耳')
  })
})
