import { createRouter, createWebHistory } from 'vue-router'

const routes = [
  { path: '/', name: 'home', component: () => import('./pages/HomeRoute.vue') },
  { path: '/graph', name: 'graph', component: () => import('./pages/GraphRoute.vue') },
  { path: '/cinema', name: 'cinema', component: () => import('./pages/CinemaRoute.vue') },
  { path: '/queen', name: 'queen', component: () => import('./pages/QueenRoute.vue') },
]

export const router = createRouter({ history: createWebHistory(), routes })
