import { useCallback, useEffect, useLayoutEffect, useRef, useState, type RefObject, type UIEventHandler } from 'react'

const BOTTOM_THRESHOLD_PX = 24

export function isNearScrollBottom(
  scrollTop: number,
  clientHeight: number,
  scrollHeight: number,
  threshold = BOTTOM_THRESHOLD_PX,
): boolean {
  return scrollHeight - scrollTop - clientHeight <= threshold
}

interface FollowLatestResult {
  readonly containerRef: RefObject<HTMLOListElement | null>
  readonly isAtBottom: boolean
  readonly newItemCount: number
  readonly onScroll: UIEventHandler<HTMLOListElement>
  readonly scrollToLatest: () => void
}

function moveToBottom(element: HTMLElement): void {
  element.scrollTop = element.scrollHeight
}

/** Keeps a live message list pinned only while the reader remains near its bottom. */
export function useFollowLatest(
  itemKeys: readonly (string | number)[],
  contentVersion: string,
): FollowLatestResult {
  const containerRef = useRef<HTMLOListElement>(null)
  const previousKeysRef = useRef<ReadonlySet<string | number>>(new Set())
  const mountedRef = useRef(false)
  const atBottomRef = useRef(true)
  const [isAtBottom, setIsAtBottom] = useState(true)
  const [newItemCount, setNewItemCount] = useState(0)

  useEffect(() => {
    mountedRef.current = true
    return () => { mountedRef.current = false }
  }, [])

  const updateBottomState = useCallback((atBottom: boolean): void => {
    atBottomRef.current = atBottom
    setIsAtBottom(atBottom)
    if (atBottom) setNewItemCount(0)
  }, [])

  const onScroll: UIEventHandler<HTMLOListElement> = useCallback((event): void => {
    const element = event.currentTarget
    updateBottomState(isNearScrollBottom(element.scrollTop, element.clientHeight, element.scrollHeight))
  }, [updateBottomState])

  const scrollToLatest = useCallback((): void => {
    const element = containerRef.current
    if (element === null) return
    moveToBottom(element)
    updateBottomState(true)
    element.focus({ preventScroll: true })
  }, [updateBottomState])

  useLayoutEffect(() => {
    const element = containerRef.current
    const previousKeys = previousKeysRef.current
    const nextKeys = new Set(itemKeys)
    const addedCount = itemKeys.reduce<number>((count, key) => count + (previousKeys.has(key) ? 0 : 1), 0)
    previousKeysRef.current = nextKeys

    if (itemKeys.length === 0) {
      atBottomRef.current = true
      queueMicrotask(() => {
        if (mountedRef.current) updateBottomState(true)
      })
      return
    }
    if (element !== null && atBottomRef.current) {
      moveToBottom(element)
      return
    }
    if (addedCount > 0) {
      queueMicrotask(() => {
        if (mountedRef.current) setNewItemCount((count) => count + addedCount)
      })
    }
  }, [contentVersion, itemKeys, updateBottomState])

  return { containerRef, isAtBottom, newItemCount, onScroll, scrollToLatest }
}
