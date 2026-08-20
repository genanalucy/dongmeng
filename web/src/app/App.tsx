import { useEffect, useMemo, useState } from 'react'
import { createBrowserMicrophoneEnvironment, MicrophoneService, MutablePcmPacketSink } from '../audio/MicrophoneService'
import { FaceToFaceController } from '../face/FaceToFaceController'
import { FaceToFacePage } from '../pages/FaceToFacePage'
import { AgentHealthService, type AgentHealthSnapshot } from '../translation/AgentHealthService'
import { LocalAgentTranslationClient } from '../translation/LocalAgentTranslationClient'
import { DeterministicMockTranslationPort, type TranslationPort } from '../translation/TranslationPort'
import { HomePage } from '../pages/HomePage'

type Page = 'home' | 'face-to-face'
type TranslationMode = 'local' | 'mock'

const initialHealth: AgentHealthSnapshot = { status: 'offline', checkedAtMs: null }

export function App(): JSX.Element {
  const [page, setPage] = useState<Page>('home')
  const [mode, setMode] = useState<TranslationMode>('local')
  const [health, setHealth] = useState<AgentHealthSnapshot>(initialHealth)
  const [packetSink] = useState(() => new MutablePcmPacketSink())
  const [microphoneService] = useState(
    () => new MicrophoneService(createBrowserMicrophoneEnvironment(), packetSink),
  )
  const [healthService] = useState(() => new AgentHealthService())
  const translationPort: TranslationPort = useMemo(
    () => mode === 'local' ? new LocalAgentTranslationClient() : new DeterministicMockTranslationPort(),
    [mode],
  )
  const controller = useMemo(() => new FaceToFaceController(translationPort), [translationPort])

  useEffect(() => {
    const unsubscribe = healthService.subscribe(setHealth)
    healthService.start()
    return () => {
      unsubscribe()
      healthService.stop()
    }
  }, [healthService])

  return page === 'home'
    ? <HomePage onOpenFaceToFace={() => setPage('face-to-face')} />
    : (
        <FaceToFacePage
          controller={controller}
          onBack={() => setPage('home')}
          microphoneService={microphoneService}
          packetSink={packetSink}
          translationMode={mode}
          agentHealth={health}
          onSelectTranslationMode={setMode}
          onCheckAgentHealth={() => { void healthService.check() }}
        />
      )
}
