import type { ReactElement } from 'react'

interface UnavailablePanelProps {
  readonly title: string
  readonly description: string
  readonly endpoint?: string
}

export function UnavailablePanel({ title, description, endpoint }: UnavailablePanelProps): ReactElement {
  return (
    <section className="unavailable-panel" aria-labelledby={`${title}-heading`}>
      <div className="unavailable-mark" aria-hidden="true">—</div>
      <div>
        <p className="eyebrow">接口待接入</p>
        <h2 id={`${title}-heading`}>{title}</h2>
        <p>{description}</p>
        {endpoint === undefined ? null : <code>{endpoint}</code>}
      </div>
    </section>
  )
}
