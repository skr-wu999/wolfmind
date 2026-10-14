import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest'

describe('WebSocket 地址配置', () => {
  beforeEach(() => vi.resetModules())
  afterEach(() => {
    vi.unstubAllEnvs()
    vi.unstubAllGlobals()
  })

  test.each(['', ' \t ', undefined])('同源部署的空地址 %j 使用页面的 HTTPS 地址', async (wsUrl) => {
    vi.stubEnv('VITE_API_BASE_URL', '')
    vi.stubEnv('VITE_WS_URL', wsUrl)
    vi.stubGlobal('location', { protocol: 'https:', host: 'wolfmind.example:8443' })

    const config = await import('../src/api/config')
    expect(config.API_BASE_URL).toBe('')
    expect(config.WS_URL).toBe('wss://wolfmind.example:8443/ws')
  })

  test.each([
    ['http:', 'ws://localhost:18081/ws'],
    ['https:', 'wss://localhost:18081/ws'],
  ])('同源部署按页面协议 %s 推导地址并保留端口', async (protocol, expected) => {
    vi.stubEnv('VITE_API_BASE_URL', '')
    vi.stubEnv('VITE_WS_URL', '')
    vi.stubGlobal('location', { protocol, host: 'localhost:18081' })

    expect((await import('../src/api/config')).WS_URL).toBe(expected)
  })

  test.each([
    ['http://127.0.0.1:8080/', 'ws://127.0.0.1:8080/ws'],
    ['https://backend.example/', 'wss://backend.example/ws'],
  ])('空 WebSocket 地址按显式 API 地址 %s 推导', async (apiUrl, expected) => {
    vi.stubEnv('VITE_API_BASE_URL', apiUrl)
    vi.stubEnv('VITE_WS_URL', '')

    expect((await import('../src/api/config')).WS_URL).toBe(expected)
  })

  test('显式 WebSocket 地址优先于 API 地址', async () => {
    vi.stubEnv('VITE_API_BASE_URL', 'https://backend.example')
    vi.stubEnv('VITE_WS_URL', 'wss://realtime.example/custom?channel=game')

    expect((await import('../src/api/config')).WS_URL).toBe('wss://realtime.example/custom?channel=game')
  })

  test('未配置环境变量时保留本地开发默认地址', async () => {
    vi.stubEnv('VITE_API_BASE_URL', undefined)
    vi.stubEnv('VITE_WS_URL', undefined)

    const config = await import('../src/api/config')
    expect(config.API_BASE_URL).toBe('http://127.0.0.1:8080')
    expect(config.WS_URL).toBe('ws://127.0.0.1:8080/ws')
  })
})
