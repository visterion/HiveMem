import { ref, type Ref } from 'vue'

const MOBILE_QUERY = '(max-width: 959px)'

/** Single source of truth for the responsive breakpoint. isMobile is reactive on resize/rotation. */
export function useLayout(): { isMobile: Ref<boolean> } {
  const mql = typeof window !== 'undefined' && typeof window.matchMedia === 'function'
    ? window.matchMedia(MOBILE_QUERY)
    : null
  const isMobile = ref(mql ? mql.matches : false)
  if (mql) {
    const update = () => { isMobile.value = mql.matches }
    if (mql.addEventListener) mql.addEventListener('change', update)
    else if (mql.addListener) mql.addListener(update) // older Safari
  }
  return { isMobile }
}
