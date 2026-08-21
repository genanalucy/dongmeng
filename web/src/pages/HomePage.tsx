import type { AgentHealthSnapshot } from '../translation/AgentHealthService'

interface HomePageProps {
  readonly onOpenSolo: () => void
  readonly onOpenFaceToFace: () => void
  readonly agentHealth: AgentHealthSnapshot
}

export function HomePage({ onOpenSolo, onOpenFaceToFace, agentHealth }: HomePageProps): JSX.Element {
  const agentMessage = agentHealth.checking
    ? '正在检测 Local Agent…'
    : agentHealth.status === 'online'
      ? 'Local Agent 已连接，真实火山翻译可用'
      : 'Local Agent 当前离线，仍可使用模拟模式'

  return (
    <main className="home-page">
      <section className="mode-section" aria-labelledby="mode-heading">
        <div className="section-intro">
          <div>
            <p className="eyebrow">CHOOSE A MODE</p>
            <h2 id="mode-heading">选择翻译方式</h2>
          </div>
          <p>根据当下场景快速开始，语言和音频设备可在模式内调整。</p>
        </div>
        <aside className={`home-agent-note home-agent-${agentHealth.status}`} aria-label="服务可用性">
          <span className="home-agent-indicator" aria-hidden="true" />
          <div><strong>服务状态</strong><p>{agentMessage}</p></div>
        </aside>
        <div className="mode-grid" aria-label="翻译模式">
          <article className="mode-card solo-mode">
            <div className="mode-card-icon solo-card-icon" aria-hidden="true"><span /></div>
            <div className="mode-card-copy">
              <p className="mode-kicker">FOCUS MODE</p>
              <h3>单人同声传译</h3>
              <p>适合会议、演讲与在线内容。连续收音并同步生成字幕和耳机译音。</p>
              <ul aria-label="单人同传能力">
                <li>连续实时字幕</li>
                <li>双耳或单耳播放</li>
              </ul>
            </div>
            <button type="button" onClick={onOpenSolo}>进入单人同传 <span aria-hidden="true">→</span></button>
          </article>
          <article className="mode-card featured-mode">
            <div className="mode-card-icon face-card-icon" aria-hidden="true"><span /><span /></div>
            <div className="mode-card-copy">
              <p className="mode-kicker">CONVERSATION MODE</p>
              <h3>面对面翻译</h3>
              <p>适合双人现场交流。两人各戴一只耳机，轮流说话并听取对方译文。</p>
              <ul aria-label="面对面翻译能力">
                <li>双向语言切换</li>
                <li>左右声道定向播放</li>
              </ul>
            </div>
            <button type="button" onClick={onOpenFaceToFace}>进入面对面翻译 <span aria-hidden="true">→</span></button>
          </article>
        </div>
      </section>

    </main>
  )
}
