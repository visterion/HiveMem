import { createRouter, createWebHistory } from 'vue-router'

const routes = [
  { path: '/', name: 'search', component: () => import('./pages/ComingSoon.vue'),
    meta: { title: 'nav.search', icon: 'search', full: false } },
  { path: '/hive', name: 'hive', component: () => import('./pages/HomeRoute.vue'),
    meta: { title: 'nav.hive', icon: 'reader', full: true } },
  { path: '/graph', name: 'graph', component: () => import('./pages/GraphRoute.vue'),
    meta: { title: 'nav.graph', icon: 'graph', full: true } },
  { path: '/realms', name: 'realms', component: () => import('./pages/ComingSoon.vue'),
    meta: { title: 'nav.realms', icon: 'realms', full: false } },
  { path: '/photos', name: 'photos', component: () => import('./pages/ComingSoon.vue'),
    meta: { title: 'nav.photos', icon: 'photos', full: true } },
  { path: '/scans', name: 'scans', component: () => import('./pages/ComingSoon.vue'),
    meta: { title: 'nav.scans', icon: 'scans', full: true } },
  { path: '/timemachine', name: 'timemachine', component: () => import('./pages/ComingSoon.vue'),
    meta: { title: 'nav.timemachine', icon: 'history', full: true } },
  { path: '/queen', name: 'queen', component: () => import('./pages/QueenRoute.vue'),
    meta: { title: 'nav.queen', icon: 'queen', full: true, role: 'admin' } },
  { path: '/settings', name: 'settings', component: () => import('./pages/ComingSoon.vue'),
    meta: { title: 'nav.settings', icon: 'settings', full: false } },
  { path: '/cinema', name: 'cinema', component: () => import('./pages/CinemaRoute.vue'),
    meta: { title: 'nav.hive', icon: 'cinema', full: true } },
]

export const router = createRouter({ history: createWebHistory(), routes })
