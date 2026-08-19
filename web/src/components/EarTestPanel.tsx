import type { Ear } from '../translation/TranslationPort'

interface EarTestPanelProps {
  readonly testedEars: ReadonlySet<Ear>
  readonly onTest: (ear: Ear) => void
}

export function EarTestPanel({ testedEars, onTest }: EarTestPanelProps): JSX.Element {
  return (
    <section className="ear-test-panel" aria-labelledby="ear-test-heading">
      <div>
        <p className="eyebrow">STEREO CHECK</p>
        <h2 id="ear-test-heading">左右耳测试</h2>
      </div>
      <div className="ear-tests">
        {(['left', 'right'] as const).map((ear) => {
          const label = ear === 'left' ? '测试左耳' : '测试右耳'
          return (
            <button key={ear} type="button" className="secondary-button" onClick={() => onTest(ear)}>
              {testedEars.has(ear) ? '✓ ' : ''}{label}
            </button>
          )
        })}
      </div>
      <p className="ear-test-note">测试信号仅应在选定耳朵播放；本 UI 初版以屏幕反馈验证声道目标，不调用真实音频设备。</p>
    </section>
  )
}
