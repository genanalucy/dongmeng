import { useEffect, useMemo, useRef, useState } from 'react'
import { createBrowserMicrophoneEnvironment, MicrophoneService, MutablePcmPacketSink } from '../audio/MicrophoneService'
import { createBrowserAudioContextFactory, StereoAudioPlayer } from '../audio/StereoAudioPlayer'
import { AppShell, type AppPage } from '../components/AppShell'
import { FaceToFaceController } from '../face/FaceToFaceController'
import { FaceToFacePage } from '../pages/FaceToFacePage'
import { SoloInterpretationPage } from '../pages/SoloInterpretationPage'
import { AgentHealthService, type AgentHealthSnapshot } from '../translation/AgentHealthService'
import { LocalAgentTranslationClient } from '../translation/LocalAgentTranslationClient'
import { DeterministicMockTranslationPort, type TranslationPort } from '../translation/TranslationPort'
import { SoloInterpretationController } from '../solo/SoloInterpretationController'
import { HomePage } from '../pages/HomePage'
import { TestConnectionPage } from '../pages/TestConnectionPage'
import { loadEndpointConfiguration, type EndpointConfiguration } from '../translation/EndpointConfiguration'

type TranslationMode = 'local' | 'mock'

const initialHealth: AgentHealthSnapshot = {
  status: 'offline',
  checkedAtMs: null,
  checking: false,
  errorMessage: null,
}

export function App(): JSX.Element {
  const [page, setPage] = useState<AppPage>('home')
  const [mode, setMode] = useState<TranslationMode>('local')
  const [health, setHealth] = useState<AgentHealthSnapshot>(initialHealth)
  const [endpointConfiguration, setEndpointConfiguration] = useState<EndpointConfiguration>(() => loadEndpointConfiguration())
  const [packetSink] = useState(() => new MutablePcmPacketSink())
  const [microphoneService] = useState(
    () => new MicrophoneService(createBrowserMicrophoneEnvironment(), packetSink),
  )
  const [healthService] = useState(() => new AgentHealthService())
  const [audioPlayer] = useState(() => new StereoAudioPlayer(createBrowserAudioContextFactory()))
  const audioPlayerLifecycleGeneration = useRef(0)
  const translationPort: TranslationPort = useMemo(
    () => mode === 'local'
      ? new LocalAgentTranslationClient({ ttsSink: audioPlayer })
      : new DeterministicMockTranslationPort(),
    [audioPlayer, mode],
  )
  const controller = useMemo(
    () => new FaceToFaceController(translationPort, audioPlayer),
    [audioPlayer, translationPort],
  )
  const soloTranslationPort = useMemo(
    () => new LocalAgentTranslationClient({ ttsSink: audioPlayer }),
    [audioPlayer],
  )
  const soloController = useMemo(
    () => new SoloInterpretationController(soloTranslationPort),
    [soloTranslationPort],
  )

  useEffect(() => {
    const unsubscribe = healthService.subscribe(setHealth)
    healthService.start()
    return () => {
      unsubscribe()
      healthService.stop()
    }
  }, [healthService])

  useEffect(() => {
    const lifecycleGeneration = audioPlayerLifecycleGeneration
    const generation = ++lifecycleGeneration.current
    return () => {
      queueMicrotask(() => {
        if (lifecycleGeneration.current === generation) {
          audioPlayer.dispose()
        }
      })
    }
  }, [audioPlayer])

  let pageContent: JSX.Element
  if (page === 'home') {
    pageContent = (
      <HomePage
        onOpenSolo={() => setPage('solo')}
        onOpenFaceToFace={() => setPage('face-to-face')}
        agentHealth={health}
      />
    )
  } else if (page === 'solo') {
    pageContent = (
      <SoloInterpretationPage
        controller={soloController}
        onBack={() => setPage('home')}
        audioPlayer={audioPlayer}
        microphoneService={microphoneService}
        packetSink={packetSink}
        agentHealth={health}
        onCheckAgentHealth={() => { void healthService.check() }}
      />
    )
  } else if (page === 'settings') {
    pageContent = <TestConnectionPage initialConfiguration={endpointConfiguration} onSaved={setEndpointConfiguration} />
  } else {
    pageContent = (
      <FaceToFacePage
        controller={controller}
        onBack={() => setPage('home')}
        audioPlayer={audioPlayer}
        microphoneService={microphoneService}
        packetSink={packetSink}
        translationMode={mode}
        agentHealth={health}
        onSelectTranslationMode={setMode}
        onCheckAgentHealth={() => { void healthService.check() }}
      />
    )
  }

  return (
    <AppShell currentPage={page} agentHealth={health} onNavigate={setPage}>
      {pageContent}
    </AppShell>
  )
}
