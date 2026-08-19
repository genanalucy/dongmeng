import { useMemo, useState } from 'react'
import { FaceToFaceController } from '../face/FaceToFaceController'
import { HomePage } from '../pages/HomePage'
import { FaceToFacePage } from '../pages/FaceToFacePage'
import { DeterministicMockTranslationPort } from '../translation/TranslationPort'

type Page = 'home' | 'face-to-face'

export function App(): JSX.Element {
  const [page, setPage] = useState<Page>('home')
  const controller = useMemo(
    () => new FaceToFaceController(new DeterministicMockTranslationPort()),
    [],
  )

  return page === 'home'
    ? <HomePage onOpenFaceToFace={() => setPage('face-to-face')} />
    : <FaceToFacePage controller={controller} onBack={() => setPage('home')} />
}
