import { fireEvent, render, screen, within } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import { HomePage } from './HomePage'

const health = { status: 'online', checkedAtMs: 1, checking: false, errorMessage: null } as const

describe('HomePage', () => {
  it('presents the hero and both translation modes', () => {
    render(<HomePage onOpenSolo={() => undefined} onOpenFaceToFace={() => undefined} agentHealth={health} />)

    expect(screen.getByRole('heading', { level: 1, name: /让每一次对话/ })).toBeInTheDocument()
    const modes = screen.getByRole('region', { name: '选择翻译方式' })
    expect(within(modes).getByRole('heading', { name: '单人同声传译' })).toBeInTheDocument()
    expect(within(modes).getByRole('heading', { name: '面对面翻译' })).toBeInTheDocument()
    expect(screen.getByLabelText('服务可用性')).toHaveTextContent('Local Agent 已连接')
  })

  it('opens solo interpretation from the hero and mode card', () => {
    const onOpenSolo = vi.fn()
    render(<HomePage onOpenSolo={onOpenSolo} onOpenFaceToFace={() => undefined} agentHealth={health} />)

    fireEvent.click(screen.getByRole('button', { name: '开始单人同传' }))
    fireEvent.click(screen.getByRole('button', { name: /进入单人同传/ }))

    expect(onOpenSolo).toHaveBeenCalledTimes(2)
  })

  it('opens face-to-face translation from both entry points', () => {
    const onOpenFaceToFace = vi.fn()
    render(<HomePage onOpenSolo={() => undefined} onOpenFaceToFace={onOpenFaceToFace} agentHealth={health} />)

    fireEvent.click(screen.getByRole('button', { name: /体验面对面翻译/ }))
    fireEvent.click(screen.getByRole('button', { name: /进入面对面翻译/ }))

    expect(onOpenFaceToFace).toHaveBeenCalledTimes(2)
  })
})
