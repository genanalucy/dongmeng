interface HomePageProps {
  readonly onOpenFaceToFace: () => void
}

export function HomePage({ onOpenFaceToFace }: HomePageProps): JSX.Element {
  return (
    <main className="home-page">
      <section className="hero">
        <p className="eyebrow">REAL-TIME AI TRANSLATION</p>
        <h1>实时 AI 翻译</h1>
        <p>为两人各戴一只耳机的面对面沟通准备的半双工翻译体验。</p>
      </section>
      <section className="mode-grid" aria-label="翻译模式">
        <article className="mode-card future-mode">
          <span aria-hidden="true">🎧</span>
          <h2>单人同声传译</h2>
          <p>会议、演讲与日常沟通。</p>
          <p className="future-note">后续功能：本轮不连接音频或翻译服务。</p>
          <button type="button" disabled>后续开放</button>
        </article>
        <article className="mode-card featured-mode">
          <span aria-hidden="true">🎧🎧</span>
          <h2>面对面翻译</h2>
          <p>两人各戴一只耳机，轮流按住说话。</p>
          <button type="button" onClick={onOpenFaceToFace}>进入面对面翻译</button>
        </article>
      </section>
      <p className="mock-notice">模拟翻译，尚未连接火山服务</p>
    </main>
  )
}
