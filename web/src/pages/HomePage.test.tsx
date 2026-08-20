import { fireEvent, render, screen } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import { HomePage } from './HomePage'

const health = { status: 'online', checkedAtMs: 1, checking: false, errorMessage: null } as const

describe('HomePage', () => {
  it('opens the solo interpretation module from the active mode card', () => {
    const onOpenSolo = vi.fn()
    render(<HomePage onOpenSolo={onOpenSolo} onOpenFaceToFace={() => undefined} agentHealth={health} />)

    fireEvent.click(screen.getByRole('button', { name: '进入单人同传' }))

    expect(onOpenSolo).toHaveBeenCalledOnce()
  })
})
