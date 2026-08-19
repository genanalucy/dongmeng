import { describe, expect, it, vi } from 'vitest'
import {
  AudioDeviceService,
  buildMicrophoneConstraints,
  type AudioDevice,
  type MediaDevicesPort,
  type MediaStreamPort,
} from './AudioDeviceService'

const input: AudioDevice = { deviceId: 'mic', groupId: 'group-1', kind: 'audioinput', label: 'MacBook Microphone' }
const output: AudioDevice = { deviceId: 'headphones', groupId: 'group-2', kind: 'audiooutput', label: 'AirPods' }
const camera: AudioDevice = { deviceId: 'camera', groupId: 'group-3', kind: 'videoinput', label: 'Camera' }

function createMediaDevices(devices: readonly AudioDevice[]): {
  readonly port: MediaDevicesPort
  readonly emitDeviceChange: () => void
  readonly stop: ReturnType<typeof vi.fn>
} {
  let listener: (() => void) | null = null
  const stop = vi.fn()
  const stream: MediaStreamPort = { getTracks: () => [{ stop }] }
  return {
    port: {
      requestMicrophone: vi.fn(async () => stream),
      enumerateDevices: vi.fn(async () => devices),
      addDeviceChangeListener: (nextListener) => {
        listener = nextListener
        return () => { listener = null }
      },
    },
    emitDeviceChange: () => listener?.(),
    stop,
  }
}

describe('AudioDeviceService', () => {
  it('requests microphone from explicit user action, stops the temporary track, and filters audio devices', async () => {
    const media = createMediaDevices([input, output, camera])
    const service = new AudioDeviceService(media.port)

    await service.requestPermission()

    expect(media.port.requestMicrophone).toHaveBeenCalledWith({ audio: true })
    expect(media.stop).toHaveBeenCalledOnce()
    expect(service.getSnapshot()).toMatchObject({
      microphonePermissionGranted: true,
      inputDevices: [input],
      outputDevices: [output],
    })
  })

  it('uses an exact selected microphone device constraint', async () => {
    const media = createMediaDevices([input, output])
    const service = new AudioDeviceService(media.port)
    await service.refreshDevices()

    await service.selectInput(input.deviceId)

    expect(media.port.requestMicrophone).toHaveBeenLastCalledWith(buildMicrophoneConstraints(input.deviceId))
    expect(service.getSnapshot().selectedInputDeviceId).toBe(input.deviceId)
  })

  it('clears a disappeared selected output and reports a disconnection without choosing another output', async () => {
    const media = createMediaDevices([input, output])
    const service = new AudioDeviceService(media.port)
    await service.refreshDevices()
    await service.selectOutput(output.deviceId)
    expect(service.getSnapshot().selectedOutputDeviceId).toBe(output.deviceId)

    media.port.enumerateDevices = vi.fn(async () => [input])
    media.emitDeviceChange()
    await vi.waitFor(() => expect(service.getSnapshot().outputDisconnected).toBe(true))

    expect(service.getSnapshot()).toMatchObject({
      selectedOutputDeviceId: null,
      outputDisconnected: true,
    })
  })

  it('returns a readable unsupported-API error through the injectable adapter boundary', async () => {
    const service = new AudioDeviceService(null)

    await expect(service.requestPermission()).rejects.toThrow('此浏览器不支持音频设备 API')
    expect(service.getSnapshot().errorMessage).toContain('此浏览器不支持音频设备 API')
  })
})
