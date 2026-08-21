import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import { defaultEndpointConfiguration } from '../translation/EndpointConfiguration'
import { TestConnectionPage } from './TestConnectionPage'

describe('TestConnectionPage', () => {
  it('shows a failed health check', async () => {
    render(
      <TestConnectionPage
        initialConfiguration={defaultEndpointConfiguration}
        onSaved={() => undefined}
        fetcher={vi.fn(async () => { throw new Error('network down') })}
      />,
    )

    fireEvent.click(screen.getByRole('button', { name: '检查连接' }))
    await waitFor(() => expect(screen.getByRole('status')).toHaveTextContent('失败：network down'))
  })

  it('derives and saves the WebSocket URL', () => {
    const onSaved = vi.fn()
    render(<TestConnectionPage initialConfiguration={defaultEndpointConfiguration} onSaved={onSaved} />)

    fireEvent.change(screen.getByRole('textbox', { name: 'Agent HTTP URL' }), { target: { value: 'https://agent.example.test/base' } })
    fireEvent.click(screen.getByRole('button', { name: '从 HTTP 推导 WS' }))
    expect(screen.getByRole('textbox', { name: 'Agent WS URL' })).toHaveValue('wss://agent.example.test/ws/translate')
    fireEvent.click(screen.getByRole('button', { name: '保存地址' }))
    expect(onSaved).toHaveBeenCalledWith(expect.objectContaining({ agentWsUrl: 'wss://agent.example.test/ws/translate' }))
  })
})
