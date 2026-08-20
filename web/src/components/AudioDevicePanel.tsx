import type { AudioDeviceSnapshot } from '../audio/AudioDeviceService'

interface AudioDevicePanelProps {
  readonly snapshot: AudioDeviceSnapshot
  readonly outputSelectionSupported: boolean
  readonly busy: boolean
  readonly actionError: string | null
  readonly onRequestPermission: () => Promise<void>
  readonly onRefresh: () => Promise<void>
  readonly onSelectInput: (deviceId: string) => Promise<void>
  readonly onSelectOutput: (deviceId: string) => Promise<void>
  readonly title?: string
  readonly description?: string
  readonly requireOutput?: boolean
}

function deviceLabel(label: string, index: number, kind: '输入' | '输出'): string {
  return label.length > 0 ? label : `${kind}设备 ${index + 1}（授权后显示名称）`
}

export function AudioDevicePanel({
  snapshot,
  outputSelectionSupported,
  busy,
  actionError,
  onRequestPermission,
  onRefresh,
  onSelectInput,
  onSelectOutput,
  title = '面对面准备',
  description = '先明确授权麦克风，再分别选择输入麦克风和耳机输出。',
  requireOutput = true,
}: AudioDevicePanelProps): JSX.Element {
  const inputReady = snapshot.microphonePermissionGranted && snapshot.selectedInputDeviceId !== null
  const outputReady = !requireOutput || snapshot.selectedOutputDeviceId !== null && !snapshot.outputDisconnected

  return (
    <section className="device-panel" aria-labelledby="device-heading">
      <div className="section-heading">
        <div>
          <p className="eyebrow">DEVICE PREPARATION</p>
          <h2 id="device-heading">{title}</h2>
        </div>
        <span className={`device-status ${inputReady && outputReady ? 'ready' : 'pending'}`}>
          {inputReady && outputReady ? '设备已准备' : '等待设备准备'}
        </span>
      </div>
      <p className="device-description">{description}</p>
      <div className="device-actions">
        <button type="button" className="secondary-button" disabled={busy} onClick={() => { void onRequestPermission() }}>
          {snapshot.microphonePermissionGranted ? '重新授权麦克风' : '授权麦克风'}
        </button>
        <button type="button" className="back-button" disabled={busy} onClick={() => { void onRefresh() }}>刷新设备</button>
      </div>
      <div className="device-select-grid">
        <label>
          输入设备
          <select
            value={snapshot.selectedInputDeviceId ?? ''}
            disabled={busy || !snapshot.microphonePermissionGranted}
            onChange={(event) => { void onSelectInput(event.target.value) }}
          >
            <option value="">请选择麦克风</option>
            {snapshot.inputDevices.map((device, index) => (
              <option key={device.deviceId} value={device.deviceId}>{deviceLabel(device.label, index, '输入')}</option>
            ))}
          </select>
        </label>
        <label>
          输出设备
          <select
            value={snapshot.selectedOutputDeviceId ?? ''}
            disabled={busy || !snapshot.microphonePermissionGranted}
            onChange={(event) => { void onSelectOutput(event.target.value) }}
          >
            <option value="">请选择耳机输出</option>
            {snapshot.outputDevices.map((device, index) => (
              <option key={device.deviceId} value={device.deviceId}>{deviceLabel(device.label, index, '输出')}</option>
            ))}
          </select>
        </label>
      </div>
      {!outputSelectionSupported && (
        <p className="fallback-message">此浏览器无法直接选择音频输出。请在 macOS 系统设置中将蓝牙耳机设为默认音频输出。</p>
      )}
      {requireOutput && snapshot.outputDisconnected && <p role="alert" className="error-message">耳机已断开，请重新连接。</p>}
      {snapshot.errorMessage !== null && <p role="alert" className="error-message">{snapshot.errorMessage}</p>}
      {actionError !== null && <p role="alert" className="error-message">{actionError}</p>}
    </section>
  )
}
