import type { AgentHealthSnapshot } from '../translation/AgentHealthService'

interface HomePageProps {
  readonly onOpenSolo: () => void
  readonly onOpenFaceToFace: () => void
  readonly agentHealth: AgentHealthSnapshot
}

export function HomePage({ onOpenSolo, onOpenFaceToFace, agentHealth }: HomePageProps): JSX.Element {
  return (
    <main className="home-page">
      <section className="hero">
        <p className="eyebrow">REAL-TIME AI TRANSLATION</p>
        <h1>实时 AI 翻译</h1>
        <p>连续单人同传与双人面对面翻译，面向桌面和移动设备的实时语音体验。</p>
      </section>
      <section className="mode-grid" aria-label="翻译模式">
        <article className="mode-card solo-mode">
          <span aria-hidden="true">🎧</span>
          <h2>单人同声传译</h2>
          <p>会议、演讲与日常沟通。</p>
          <p className="future-note">连续录音、实时字幕与可选双耳同传语音。</p>
          <button type="button" onClick={onOpenSolo}>进入单人同传</button>
        </article>
        <article className="mode-card featured-mode">
          <span aria-hidden="true">🎧🎧</span>
          <h2>面对面翻译</h2>
          <p>两人各戴一只耳机，轮流按住说话。</p>
          <button type="button" onClick={onOpenFaceToFace}>进入面对面翻译</button>
        </article>
      </section>
      <p className={agentHealth.status === 'online' ? 'agent-badge' : 'mock-notice'}>
        {agentHealth.checking
          ? '正在检测 Local Agent…'
          : agentHealth.status === 'online'
            ? 'Local Agent 在线 · 真实火山翻译可用'
            : 'Local Agent 离线 · 仍可使用模拟模式'}
      </p>
    </main>
  )
}
