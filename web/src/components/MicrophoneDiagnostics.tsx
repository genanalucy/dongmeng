import type { MicrophoneSnapshot } from '../audio/MicrophoneService'

interface MicrophoneDiagnosticsProps {
  readonly snapshot: MicrophoneSnapshot
}

function formatSampleRate(sampleRate: number | null): string {
  return sampleRate === null ? '等待采集' : `${sampleRate} Hz`
}

export function MicrophoneDiagnostics({ snapshot }: MicrophoneDiagnosticsProps): JSX.Element {
  const levelPercent = Math.round(snapshot.audioLevel * 100)

  return (
    <section className="microphone-diagnostics" aria-labelledby="microphone-diagnostics-heading">
      <div className="section-heading">
        <div>
          <p className="eyebrow">MICROPHONE PCM · MOCK OBSERVER</p>
          <h2 id="microphone-diagnostics-heading">实时采集状态</h2>
        </div>
        <span className={`capture-status ${snapshot.state}`}>{captureStateLabel[snapshot.state]}</span>
      </div>
      <p className="device-description">PCM 包仅在浏览器内统计，不上传、不连接 localhost，也不接触 AST 私有协议。</p>
      <dl className="capture-metrics">
        <div><dt>Input sample rate</dt><dd>{formatSampleRate(snapshot.inputSampleRate)}</dd></div>
        <div><dt>AST rate</dt><dd>{snapshot.astSampleRate} Hz</dd></div>
        <div><dt>Packet</dt><dd>{snapshot.packetDurationMs} ms</dd></div>
        <div><dt>最近包</dt><dd>{snapshot.latestPacketBytes} bytes</dd></div>
        <div><dt>Packet count</dt><dd>{snapshot.packetCount}</dd></div>
        <div className="audio-level-metric">
          <dt>Audio level</dt>
          <dd>
            <progress max={100} value={levelPercent} aria-label="Audio level" />
            <span>{levelPercent}%</span>
          </dd>
        </div>
      </dl>
      {snapshot.errorMessage !== null && <p role="alert" className="error-message">{snapshot.errorMessage}</p>}
    </section>
  )
}

const captureStateLabel: Readonly<Record<MicrophoneSnapshot['state'], string>> = {
  idle: '等待 PTT',
  starting: '正在启动',
  capturing: '采集中',
  error: '采集错误',
  disposed: '已释放',
}
