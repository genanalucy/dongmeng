import type { ReactNode } from 'react'
import type { AgentHealthSnapshot } from '../translation/AgentHealthService'

export type AppPage = 'home' | 'solo' | 'face-to-face'

interface AppShellProps {
  readonly currentPage: AppPage
  readonly agentHealth: AgentHealthSnapshot
  readonly onNavigate: (page: AppPage) => void
  readonly children: ReactNode
}

const navigationItems: readonly { readonly page: AppPage; readonly label: string; readonly shortLabel: string }[] = [
  { page: 'home', label: '首页', shortLabel: '首页' },
  { page: 'solo', label: '单人同传', shortLabel: '单人' },
  { page: 'face-to-face', label: '面对面', shortLabel: '面对面' },
]

const healthLabels: Readonly<Record<AgentHealthSnapshot['status'], string>> = {
  online: 'Agent 在线',
  offline: 'Agent 离线',
}

export function AppShell({ currentPage, agentHealth, onNavigate, children }: AppShellProps): JSX.Element {
  const healthLabel = agentHealth.checking ? 'Agent 检测中' : healthLabels[agentHealth.status]

  const renderNavigation = (className: string, label: string, useShortLabels = false): JSX.Element => (
    <nav className={className} aria-label={label}>
      {navigationItems.map((item) => (
        <button
          className="app-nav-item"
          type="button"
          key={item.page}
          aria-label={useShortLabels ? item.shortLabel : item.label}
          aria-current={currentPage === item.page ? 'page' : undefined}
          onClick={() => onNavigate(item.page)}
        >
          <span className={`nav-icon nav-icon-${item.page}`} aria-hidden="true" />
          <span className="nav-label-full">{item.label}</span>
          <span className="nav-label-short">{item.shortLabel}</span>
        </button>
      ))}
    </nav>
  )

  return (
    <div className="app-shell">
      <header className="app-header">
        <div className="app-header-inner">
          <button className="brand" type="button" onClick={() => onNavigate('home')} aria-label="返回首页">
            <span className="brand-mark" aria-hidden="true"><span /></span>
            <span className="brand-copy">
              <strong>VERBA</strong>
              <small>REAL-TIME INTERPRETATION</small>
            </span>
          </button>
          {renderNavigation('desktop-nav', '主导航')}
          <div className={`agent-status agent-status-${agentHealth.status}`} role="status">
            <span aria-hidden="true" />
            {healthLabel}
          </div>
        </div>
      </header>
      <div className="app-content">{children}</div>
      {renderNavigation('mobile-nav', '移动端主导航', true)}
    </div>
  )
}
