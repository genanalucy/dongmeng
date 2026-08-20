import type { CSSProperties, ReactNode } from 'react'

export interface BrandMarkProps {
  readonly className?: string
  readonly decorative?: boolean
}

export interface BrandDefinition {
  readonly name: string
  readonly shortName: string
  readonly tagline: string
  readonly primaryColor: string
  readonly accentColor: string
  readonly renderMark: (props: BrandMarkProps) => ReactNode
  readonly renderWordmark: () => ReactNode
}

/**
 * Web 品牌的唯一配置入口。Android 可复用同名字段及色值语义；替换视觉资产时，
 * 只需改写 renderMark/renderWordmark，无需改动壳层或业务页面。
 */
export const brand: BrandDefinition = {
  name: 'Verba 实时翻译',
  shortName: 'VERBA',
  tagline: '实时翻译，专注对话',
  primaryColor: '#246b63',
  accentColor: '#d97738',
  renderMark: ({ className, decorative = true }) => (
    <span className={className} aria-hidden={decorative || undefined}>
      <span>V</span>
    </span>
  ),
  renderWordmark: () => <>{'VERBA'}</>,
}

export const brandThemeStyle = {
  '--brand-primary': brand.primaryColor,
  '--brand-accent': brand.accentColor,
} as CSSProperties
