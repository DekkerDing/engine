import { useCallback, useEffect, useRef, useState } from 'react'

/**
 * 通用轮询 hook —— 仪表盘健康刷新 / 文档列表状态流转共用。
 *
 * 【教学注释 · 轮询的三个细节】
 * 1. 首次立即执行（不等第一个间隔）；
 * 2. 上一次请求未完成不发起新一次（inFlight 防重入——慢接口时避免请求堆积）;
 * 3. 卸载时清定时器 + 让进行中的请求结果作废（unmounted 后不再 setState，
 *    否则 React 18 会在开发态警告内存泄漏）。
 *
 * @param fetcher 数据获取函数（内部已带错误处理时返回 null 表示失败）
 * @param intervalMs 轮询间隔
 */
export function usePolling<T>(
  fetcher: () => Promise<T>,
  intervalMs: number,
): { data: T | null; error: unknown; refresh: () => void } {
  const [data, setData] = useState<T | null>(null)
  const [error, setError] = useState<unknown>(null)
  const unmountedRef = useRef(false)
  const inFlightRef = useRef(false)
  // fetcher 经常是内联箭头函数（每次渲染新引用），用 ref 保存最新值，
  // 避免 useEffect 因依赖变化反复重启定时器
  const fetcherRef = useRef(fetcher)
  fetcherRef.current = fetcher

  const tick = useCallback(async () => {
    if (inFlightRef.current) return
    inFlightRef.current = true
    try {
      const result = await fetcherRef.current()
      if (!unmountedRef.current) {
        setData(result)
        setError(null)
      }
    } catch (e) {
      if (!unmountedRef.current) setError(e)
    } finally {
      inFlightRef.current = false
    }
  }, [])

  useEffect(() => {
    unmountedRef.current = false
    void tick()
    const timer = window.setInterval(tick, intervalMs)
    return () => {
      unmountedRef.current = true
      window.clearInterval(timer)
    }
  }, [tick, intervalMs])

  return { data, error, refresh: () => void tick() }
}
