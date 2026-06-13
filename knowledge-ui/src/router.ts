import { createRouter, createWebHistory, type RouteRecordRaw } from 'vue-router'

const routes: RouteRecordRaw[] = [
  { path: '/', name: 'search', meta: { title: 'nav.search', icon: 'search', full: false, mobilePrimary: 'panel' },
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
