import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import vuetify from 'vite-plugin-vuetify'
import { VitePWA } from 'vite-plugin-pwa'

export default defineConfig({
  plugins: [
    vue({
      template: {
        compilerOptions: {
          isCustomElement: (tag) =>
            (tag.startsWith('Tres') && tag !== 'TresCanvas') || tag === 'primitive',
        },
      },
    }),
    vuetify({ autoImport: true }),
    VitePWA({
      registerType: 'prompt',
      // Generates all icons from the existing brand SVG at build time and injects the
      // manifest icon entries + apple-touch-icon <link> into index.html.
      pwaAssets: { preset: 'minimal-2023', image: 'public/favicon.svg' },
      manifest: {
        name: 'HiveMem',
        short_name: 'HiveMem',
        description: 'Local-first knowledge graph — your second brain',
        theme_color: '#0a0a1a',
        background_color: '#0a0a1a',
        display: 'standalone',
        orientation: 'any',
        start_url: '/',
      },
      workbox: {
        globPatterns: ['**/*.{js,css,html,svg,png,woff2}'],
        // Do NOT serve the SPA index.html for server-rendered routes — otherwise an
        // expired session can never reach /login or the OAuth consent page.
        navigateFallbackDenylist: [
          /^\/login/, /^\/logout/, /^\/oauth\//, /^\/admin/, /^\/api\//,
          /^\/mcp/, /^\/hooks/, /^\/sync/, /^\/vistierie/, /^\/\.well-known\//,
        ],
        // Intentionally NO runtimeCaching for /api: unmatched requests pass through the
        // SW untouched (network-only, uncached) and keep granular XHR upload progress.
      },
    }),
  ],
  server: {
    host: '0.0.0.0',
    port: 5173,
    // Dev only: proxy same-origin so the session cookie works. /login+/logout are
    // required to actually obtain the cookie on localhost:5173.
    proxy: {
      '/api': 'http://localhost:8421',
      '/login': 'http://localhost:8421',
      '/logout': 'http://localhost:8421',
    },
  },
})
