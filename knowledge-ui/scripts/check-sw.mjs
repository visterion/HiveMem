// Build-regression guard: fail if the compiled service worker would hijack server-rendered
// routes (login/oauth/etc.) or intercept /api. Run after `npm run build`.
import { readFileSync } from 'node:fs'

const sw = readFileSync(new URL('../dist/sw.js', import.meta.url), 'utf8')
const problems = []

// Workbox compiles navigateFallbackDenylist into a `denylist:[/regex/,...]` array on the
// NavigationRoute. Assert every server-rendered prefix we must never hijack is present as a
// regex source in the compiled SW — a missing one means that route would be served the SPA shell.
const requiredDenylist = [
  '\\/login', '\\/logout', '\\/oauth\\/', '\\/admin', '\\/api\\/',
  '\\/mcp', '\\/hooks', '\\/sync', '\\/vistierie', '\\/\\.well-known\\/',
]
for (const src of requiredDenylist) {
  if (!sw.includes(src)) {
    problems.push(`sw.js navigate denylist is missing ${src} — that route would be hijacked by the SPA shell`)
  }
}

// No runtime caching is allowed: an /api runtime handler would break granular XHR upload
// progress. generateSW only emits these strategy names when runtimeCaching is configured.
if (/\b(NetworkOnly|NetworkFirst|StaleWhileRevalidate|CacheFirst|CacheOnly)\b/.test(sw) || sw.includes('runtimeCaching')) {
  problems.push('sw.js contains a runtime caching strategy — no /api runtime route is allowed (would break upload progress)')
}

if (problems.length) { console.error('check-sw FAILED:\n- ' + problems.join('\n- ')); process.exit(1) }
console.log('check-sw OK')
