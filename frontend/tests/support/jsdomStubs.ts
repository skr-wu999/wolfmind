import { vi } from 'vitest'

/**
 * jsdom 缺失能力的替身。
 *
 * 页面测试要用真实适配器与真实后端，而 jsdom 不实现若干浏览器 API。
 * 这些替身**不是为了绕过被测代码**，而是补上 jsdom 的空缺：
 * - `canvas.getContext('2d')` 返回 null，而村庄场景组件会画它；
 * - `matchMedia` 不存在，而响应式布局判断要用它；
 * - `ResizeObserver` 不存在；
 * - `HTMLDialogElement.showModal/close` 未实现，而弹窗用的是 `<dialog>`；
 * - jest-dom 不含 `HTMLDialogElement` 时上面的分支会跳过。
 *
 * 抽成一个模块而不是每个测试各写一遍：这些桩与"被测的是什么"无关，
 * 复制三份之后，改一个（例如 jsdom 升级后不再需要）会漏掉另外两份。
 */
export function installJsdomStubs(): void {
  const context = {
    imageSmoothingEnabled: false,
    fillStyle: '',
    strokeStyle: '',
    lineWidth: 1,
    globalAlpha: 1,
    clearRect: vi.fn(),
    fillRect: vi.fn(),
    strokeRect: vi.fn(),
    drawImage: vi.fn(),
    beginPath: vi.fn(),
    closePath: vi.fn(),
    moveTo: vi.fn(),
    lineTo: vi.fn(),
    fill: vi.fn(),
    stroke: vi.fn(),
    arc: vi.fn(),
    save: vi.fn(),
    restore: vi.fn(),
    translate: vi.fn(),
    scale: vi.fn(),
    rotate: vi.fn(),
    setTransform: vi.fn(),
    createLinearGradient: () => ({ addColorStop: vi.fn() }),
  }
  Object.defineProperty(HTMLCanvasElement.prototype, 'getContext', {
    configurable: true,
    value: () => context,
  })

  vi.stubGlobal('requestAnimationFrame', vi.fn(() => 1))
  vi.stubGlobal('cancelAnimationFrame', vi.fn())
  vi.stubGlobal('matchMedia', vi.fn(() => ({
    matches: false,
    addEventListener: vi.fn(),
    removeEventListener: vi.fn(),
    addListener: vi.fn(),
    removeListener: vi.fn(),
  })))
  vi.stubGlobal('ResizeObserver', class {
    observe() {}
    unobserve() {}
    disconnect() {}
  })

  const dialogPrototype = globalThis.HTMLDialogElement?.prototype
  if (dialogPrototype) {
    Object.defineProperty(dialogPrototype, 'showModal', { configurable: true, value() {} })
    Object.defineProperty(dialogPrototype, 'close', { configurable: true, value() {} })
  }
}

/**
 * 让 jsdom 里的 `WebSocket` 用 Node 自带的那一个。
 *
 * `GameSocket` 直接用全局 `WebSocket`，而 jsdom 的实现不真正建连
 * （它只在同源下模拟），因此真实端点测试必须换成 Node 的实现。
 * **这不是"绕过 jsdom"**：被测的实时通道本来就跑在浏览器的 WebSocket 上，
 * 用 Node 的实现是能拿到的最接近的替代。
 */
export function installNodeWebSocket(): void {
  const NativeWebSocket = globalThis.WebSocket
  if (!NativeWebSocket) {
    throw new Error('当前 Node 没有全局 WebSocket；实时通道的页面测试无法运行')
  }
  vi.stubGlobal('WebSocket', NativeWebSocket)
}

/** 内存 localStorage（node/jsdom 都可能没有可写实现）。 */
export function installStorage(): Map<string, string> {
  const store = new Map<string, string>()
  vi.stubGlobal('localStorage', {
    getItem: (k: string) => store.get(k) ?? null,
    setItem: (k: string, v: string) => void store.set(k, v),
    removeItem: (k: string) => void store.delete(k),
  })
  return store
}
