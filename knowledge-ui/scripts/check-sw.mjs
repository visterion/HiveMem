// Fails the build if the generated service worker would hijack server-rendered routes
// or intercept /api. Run after `npm run build`.
import { readFileSync } from 'node:fs'

const sw = readFileSync(new URL('../dist/sw.js', import.meta.url), 'utf8')
const problems = []
if (!sw.includes('navigateFallbackDenylist') && !/\/login/.test(sw)) {
  problems.push('sw.js has no navigateFallback denylist for /login — server routes will be hijacked')
}
if (/NetworkOnly|runtimeCaching[\s\S]*\/api/.test(sw)) {
  problems.push('sw.js registers an /api runtime route — upload progress may break')
}
if (problems.length) { console.error('check-sw FAILED:\n- ' + problems.join('\n- ')); process.exit(1) }
console.log('check-sw OK')
