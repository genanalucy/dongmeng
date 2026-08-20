import { fireEvent, render, screen, within } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import { brand } from '../brand'
import { AppShell } from './AppShell'

const onlineHealth = { status: 'online', checkedAtMs: 1, checking: false, errorMessage: null } as const

describe('AppShell', () => {
  it('renders brand, agent status, and marks the current page in both navigations', () => {
    render(
      <AppShell currentPage="solo" agentHealth={onlineHealth} onNavigate={() => undefined}>
        <main>页面内容</main>
      </AppShell>,
    )

    expect(screen.getByRole('button', { name: `${brand.name}，返回首页` })).toHaveTextContent(brand.shortName)
    expect(screen.getByRole('status')).toHaveTextContent('Agent 在线')

    const desktopNavigation = screen.getByRole('navigation', { name: '主导航' })
    const mobileNavigation = screen.getByRole('navigation', { name: '移动端主导航' })
    expect(within(desktopNavigation).getByRole('button', { name: '单人同传' })).toHaveAttribute('aria-current', 'page')
    expect(within(mobileNavigation).getByRole('button', { name: '单人' })).toHaveAttribute('aria-current', 'page')
    expect(within(desktopNavigation).getByRole('button', { name: '首页' })).not.toHaveAttribute('aria-current')
  })

  it('navigates from desktop navigation, mobile navigation, and the brand', () => {
    const onNavigate = vi.fn()
    render(
      <AppShell currentPage="home" agentHealth={onlineHealth} onNavigate={onNavigate}>
        <main>页面内容</main>
      </AppShell>,
    )

    fireEvent.click(within(screen.getByRole('navigation', { name: '主导航' })).getByRole('button', { name: '面对面' }))
    fireEvent.click(within(screen.getByRole('navigation', { name: '移动端主导航' })).getByRole('button', { name: '单人' }))
    fireEvent.click(screen.getByRole('button', { name: `${brand.name}，返回首页` }))

    expect(onNavigate).toHaveBeenNthCalledWith(1, 'face-to-face')
    expect(onNavigate).toHaveBeenNthCalledWith(2, 'solo')
    expect(onNavigate).toHaveBeenNthCalledWith(3, 'home')
  })
})
