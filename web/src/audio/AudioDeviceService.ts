export interface AudioDevice {
  readonly deviceId: string
  readonly groupId: string
  readonly kind: MediaDeviceKind
  readonly label: string
}

export interface MediaStreamTrackPort {
  stop(): void
}

export interface MediaStreamPort {
  getTracks(): readonly MediaStreamTrackPort[]
}

export interface MediaDevicesPort {
  requestMicrophone(constraints: MediaStreamConstraints): Promise<MediaStreamPort>
  enumerateDevices(): Promise<readonly AudioDevice[]>
  addDeviceChangeListener(listener: () => void): () => void
}

export interface AudioDeviceSnapshot {
  readonly inputDevices: readonly AudioDevice[]
  readonly outputDevices: readonly AudioDevice[]
  readonly selectedInputDeviceId: string | null
  readonly selectedOutputDeviceId: string | null
  readonly microphonePermissionGranted: boolean
  readonly outputDisconnected: boolean
  readonly errorMessage: string | null
}

export interface AudioDeviceServicePort {
  getSnapshot(): AudioDeviceSnapshot
  subscribe(listener: (snapshot: AudioDeviceSnapshot) => void): () => void
  requestPermission(): Promise<void>
  refreshDevices(): Promise<void>
  selectInput(deviceId: string): Promise<void>
  selectOutput(deviceId: string): Promise<void>
  clearOutputSelection(): void
  dispose(): void
}

export class AudioDeviceServiceError extends Error {
  public constructor(message: string) {
    super(message)
    this.name = 'AudioDeviceServiceError'
  }
}

export function buildMicrophoneConstraints(deviceId: string): MediaStreamConstraints {
  return {
    audio: {
      deviceId: { exact: deviceId },
      channelCount: 1,
      echoCancellation: true,
      noiseSuppression: true,
      autoGainControl: true,
    },
  }
}

export class AudioDeviceService implements AudioDeviceServicePort {
  private readonly listeners = new Set<(snapshot: AudioDeviceSnapshot) => void>()
  private readonly removeDeviceChangeListener: (() => void) | null
  private inputDevices: readonly AudioDevice[] = []
  private outputDevices: readonly AudioDevice[] = []
  private selectedInputDeviceId: string | null = null
  private selectedOutputDeviceId: string | null = null
  private microphonePermissionGranted = false
  private outputDisconnected = false
  private errorMessage: string | null = null

  public constructor(private readonly mediaDevices: MediaDevicesPort | null) {
    this.removeDeviceChangeListener = mediaDevices?.addDeviceChangeListener(() => {
      void this.refreshDevices()
    }) ?? null

    if (mediaDevices === null) {
      this.errorMessage = '此浏览器不支持音频设备 API，无法准备麦克风或耳机。'
    }
  }

  public getSnapshot(): AudioDeviceSnapshot {
    return {
      inputDevices: [...this.inputDevices],
      outputDevices: [...this.outputDevices],
      selectedInputDeviceId: this.selectedInputDeviceId,
      selectedOutputDeviceId: this.selectedOutputDeviceId,
      microphonePermissionGranted: this.microphonePermissionGranted,
      outputDisconnected: this.outputDisconnected,
      errorMessage: this.errorMessage,
    }
  }

  public subscribe(listener: (snapshot: AudioDeviceSnapshot) => void): () => void {
    this.listeners.add(listener)
    listener(this.getSnapshot())
    return () => this.listeners.delete(listener)
  }

  public async requestPermission(): Promise<void> {
    const mediaDevices = this.requireMediaDevices()
    try {
      const stream = await mediaDevices.requestMicrophone({ audio: true })
      stream.getTracks().forEach((track) => track.stop())
      this.microphonePermissionGranted = true
      this.errorMessage = null
      await this.refreshDevices()
    } catch (error: unknown) {
      this.microphonePermissionGranted = false
      this.errorMessage = describeMicrophoneError(error)
      this.emit()
      throw new AudioDeviceServiceError(this.errorMessage)
    }
  }

  public async refreshDevices(): Promise<void> {
    const mediaDevices = this.requireMediaDevices()
    try {
      const devices = await mediaDevices.enumerateDevices()
      this.inputDevices = devices.filter((device) => device.kind === 'audioinput')
      this.outputDevices = devices.filter((device) => device.kind === 'audiooutput')

      if (this.selectedInputDeviceId !== null && !this.hasInput(this.selectedInputDeviceId)) {
        this.selectedInputDeviceId = null
      }
      if (this.selectedOutputDeviceId !== null && !this.hasOutput(this.selectedOutputDeviceId)) {
        this.selectedOutputDeviceId = null
        this.outputDisconnected = true
      }

      this.errorMessage = null
      this.emit()
    } catch (error: unknown) {
      this.errorMessage = `无法刷新音频设备：${describeUnknownError(error)}`
      this.emit()
      throw new AudioDeviceServiceError(this.errorMessage)
    }
  }

  public async selectInput(deviceId: string): Promise<void> {
    const mediaDevices = this.requireMediaDevices()
    if (!this.hasInput(deviceId)) {
      throw this.fail(`找不到所选输入设备。请刷新设备列表后重试。`)
    }

    try {
      const stream = await mediaDevices.requestMicrophone(buildMicrophoneConstraints(deviceId))
      stream.getTracks().forEach((track) => track.stop())
      this.microphonePermissionGranted = true
      this.selectedInputDeviceId = deviceId
      this.errorMessage = null
      this.emit()
    } catch (error: unknown) {
      this.errorMessage = `无法使用所选输入设备：${describeMicrophoneError(error)}`
      this.emit()
      throw new AudioDeviceServiceError(this.errorMessage)
    }
  }

  public async selectOutput(deviceId: string): Promise<void> {
    this.requireMediaDevices()
    if (!this.hasOutput(deviceId)) {
      throw this.fail('找不到所选输出设备。请刷新设备列表后重试。')
    }

    this.selectedOutputDeviceId = deviceId
    this.outputDisconnected = false
    this.errorMessage = null
    this.emit()
  }

  public clearOutputSelection(): void {
    this.selectedOutputDeviceId = null
    this.emit()
  }

  public dispose(): void {
    this.removeDeviceChangeListener?.()
    this.listeners.clear()
  }

  private requireMediaDevices(): MediaDevicesPort {
    if (this.mediaDevices === null) {
      throw this.fail('此浏览器不支持音频设备 API，无法准备麦克风或耳机。')
    }
    return this.mediaDevices
  }

  private fail(message: string): AudioDeviceServiceError {
    this.errorMessage = message
    this.emit()
    return new AudioDeviceServiceError(message)
  }

  private hasInput(deviceId: string): boolean {
    return this.inputDevices.some((device) => device.deviceId === deviceId)
  }

  private hasOutput(deviceId: string): boolean {
    return this.outputDevices.some((device) => device.deviceId === deviceId)
  }

  private emit(): void {
    const snapshot = this.getSnapshot()
    this.listeners.forEach((listener) => listener(snapshot))
  }
}

export function createBrowserMediaDevicesPort(
  navigatorLike: Pick<Navigator, 'mediaDevices'> = navigator,
): MediaDevicesPort | null {
  const mediaDevices = navigatorLike.mediaDevices
  if (mediaDevices === undefined) {
    return null
  }

  return {
    requestMicrophone: async (constraints) => mediaDevices.getUserMedia(constraints),
    enumerateDevices: async () => mediaDevices.enumerateDevices(),
    addDeviceChangeListener: (listener) => {
      mediaDevices.addEventListener('devicechange', listener)
      return () => mediaDevices.removeEventListener('devicechange', listener)
    },
  }
}

function describeMicrophoneError(error: unknown): string {
  if (error instanceof DOMException && error.name === 'NotAllowedError') {
    return '麦克风权限被拒绝。请在浏览器地址栏或 macOS 隐私设置中允许麦克风后重新授权。'
  }
  return describeUnknownError(error)
}

function describeUnknownError(error: unknown): string {
  return error instanceof Error ? error.message : '发生未知错误。'
}
