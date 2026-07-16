import { createRouter, createWebHistory, type RouteRecordRaw, type RouteLocationNormalized } from 'vue-router'
import { useAuthStore } from './stores/auth'
import { useCellStore } from './stores/cell'
import { useCanvasStore } from './stores/canvas'

const routes: RouteRecordRaw[] = [
  { path: '/', name: 'search', meta: { title: 'nav.search', icon: 'search', full: false },
    components: {
      default: () => import('./components/knowledge/KnowledgeReader.vue'),
      panel: () => import('./components/knowledge/SearchPanel.vue'),
      inspector: () => import('./components/knowledge/CellInspector.vue'),
    } },
  { path: '/hive', name: 'hive', component: () => import('./pages/HomeRoute.vue'),
    meta: { title: 'nav.hive', icon: 'reader', full: true } },
  { path: '/graph', name: 'graph', component: () => import('./pages/GraphRoute.vue'),
    meta: { title: 'nav.graph', icon: 'graph', full: true } },
  { path: '/realms', name: 'realms', meta: { title: 'nav.realms', icon: 'realms', full: false },
    components: {
      default: () => import('./components/realms/RealmsStage.vue'),
      panel: () => import('./components/realms/RealmsPanel.vue'),
    } },
  { path: '/photos', name: 'photos', component: () => import('./pages/PhotosRoute.vue'),
    meta: { title: 'nav.photos', icon: 'photos', full: true } },
  { path: '/scans', name: 'scans', meta: { title: 'nav.scans', icon: 'scans', full: false },
    components: {
      default: () => import('./components/scans/ScansResults.vue'),
      panel: () => import('./components/scans/ScansPanel.vue'),
    } },
  { path: '/upload', name: 'upload', component: () => import('./pages/UploadRoute.vue'),
    meta: { title: 'nav.upload', icon: 'upload', full: true } },
  { path: '/timemachine', name: 'timemachine', component: () => import('./pages/TimeMachineRoute.vue'),
    meta: { title: 'nav.timemachine', icon: 'history', full: true } },
  { path: '/queen', name: 'queen', component: () => import('./pages/QueenRoute.vue'),
    meta: { title: 'nav.queen', icon: 'queen', full: true, role: 'admin' } },
  { path: '/settings', name: 'settings', component: () => import('./pages/SettingsRoute.vue'),
    meta: { title: 'nav.settings', icon: 'settings', full: false } },
  { path: '/cinema', name: 'cinema', component: () => import('./pages/CinemaRoute.vue'),
    meta: { title: 'nav.cinema', icon: 'cinema', full: true } },
]

export const router = createRouter({ history: createWebHistory(), routes })

// UX guard only — the backend enforces the real role ACL. Redirect users whose
// role is already known to be non-admin; an unknown role (initial deep link
// before wake_up resolves) passes, since AppShell gates rendering on auth.
export function adminGuard(to: RouteLocationNormalized) {
  if (to.meta.role !== 'admin') return true
  const auth = useAuthStore()
  if (auth.role && auth.role !== 'admin') return { name: 'search' }
  return true
}

router.beforeEach(adminGuard)

// The inspector/panel selection (Pinia cell store) and the graph camera focus must not
// stick across a view change — otherwise the search inspector or graph panel keeps
// showing a stale cell from the previous route. Only a route-NAME change counts: Task 8's
// ?cell=/?realm= query mirroring on the search route pushes query-only navigations
// (same name) that must keep the selection. `from.name === undefined` is the initial
// navigation on app boot — nothing to clear yet.
// Exception: CellInspector's "Im Graph zeigen" pushes search -> graph and wants to KEEP
// the selection for that one hop; it sets cellStore.preserveOnce before the push, and this
// guard consumes the flag exactly once.
router.afterEach((to, from) => {
  if (to.name !== from.name && from.name !== undefined) {
    const cellStore = useCellStore()
    if (cellStore.preserveOnce) {
      cellStore.preserveOnce = false
      return
    }
    cellStore.clear()
    useCanvasStore().setFocus(null)
  }
})
