import type { Ear } from '../translation/TranslationPort'

interface EarTestPanelProps {
  readonly testedEars: ReadonlySet<Ear>
  readonly disabled: boolean
  readonly errorMessage: string | null
  readonly onTest: (ear: Ear) => Promise<void>
}

export function EarTestPanel({ testedEars, disabled, errorMessage, onTest }: EarTestPanelProps): JSX.Element {
  return (
    <section className="ear-test-panel" aria-labelledby="ear-test-heading">
      <div>
        <p className="eyebrow">STEREO CHECK</p>
        <h2 id="ear-test-heading">左右耳测试</h2>
      </div>
      <div className="ear-tests">
        {(['left', 'right'] as const).map((ear) => {
          const label = ear === 'left' ? '测试左耳' : '测试右耳'
          const passedLabel = ear === 'left' ? '✓ 左耳正常' : '✓ 右耳正常'
          return (
            <button
              key={ear}
              type="button"
              className="secondary-button"
              disabled={disabled}
              onClick={() => { void onTest(ear) }}
            >
              {testedEars.has(ear) ? passedLabel : label}
            </button>
          )
        })}
      </div>
      <p className="ear-test-note">点击后会播放短测试音：左测仅左声道有声，右测仅右声道有声。只有成功开始播放后才会标记通过。</p>
      {errorMessage !== null && <p role="alert" className="error-message">{errorMessage}</p>}
    </section>
  )
}
