import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import { App } from './app/App'
import './styles.css'

const rootElement = document.getElementById('root')
if (rootElement === null) {
  throw new Error('找不到应用根节点。')
}

createRoot(rootElement).render(
  <StrictMode>
    <App />
  </StrictMode>,
)
