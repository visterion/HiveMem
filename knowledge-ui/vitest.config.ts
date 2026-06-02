import { defineConfig } from 'vitest/config'
import vue from '@vitejs/plugin-vue'

// Top-level Vite is 8.x but Vitest 2.x bundles Vite 5.x, so the Plugin type
// from @vitejs/plugin-vue doesn't match vitest's internal Vite types.
// Runtime behaviour is fine; cast to bypass the structural mismatch.
export default defineConfig({
  plugins: [vue() as any],
  test: {
    environment: 'happy-dom',
    globals: true,
    include: ['tests/unit/**/*.spec.ts'],
    // Run vuetify through Vite's transform pipeline so CSS side-effects are handled
    server: {
      deps: {
        inline: ['vuetify'],
      },
    },
  },
})
