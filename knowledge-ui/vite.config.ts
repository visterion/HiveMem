import { defineConfig, loadEnv } from 'vite'
import vue from '@vitejs/plugin-vue'
import vuetify from 'vite-plugin-vuetify'
import { VitePWA } from 'vite-plugin-pwa'

export default defineConfig(({ mode }) => {
  const proxyTarget =
    loadEnv(mode, process.cwd(), 'VITE_').VITE_PROXY_TARGET ?? 'http://localhost:8421'

  return {
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
      // Override the target via VITE_PROXY_TARGET (e.g. in an untracked .env.local)
      // to debug the UI against a remote backend.
      proxy: Object.fromEntries(
        ['/api', '/mcp', '/login', '/logout'].map((path) => [
          path,
          {
            target: proxyTarget,
            // Keep the Host header: the backend builds its absolute /login redirect from
            // it, so changeOrigin would bounce the browser to the backend origin and out
            // of the dev server.
            changeOrigin: false,
            // Chrome sends Origin on every POST, even same-origin. A remote backend's
            // CORS allowlist does not contain this dev origin and answers 403 "Invalid
            // CORS request"; dropping the header makes the request non-CORS, which the
            // backend accepts. No-op when the target is localhost.
            configure: (proxy: any) => {
              proxy.on('proxyReq', (proxyReq: any) => proxyReq.removeHeader('origin'))
            },
          },
        ]),
      ),
    },
  }
})
