# HiveMem 9.2.1

Bugfix: the web UI (`knowledge-ui`) hung forever on the **"Connecting…"** splash
when served from a production build.

## Root cause

`knowledge-ui/src/api/env.ts` read the Vite env bag indirectly:

```ts
const meta = import.meta as unknown as { [k: string]: ViteEnvBag }
const BAG = meta['env']   // <-- Vite does NOT statically replace this form
```

Vite only inlines the literal `import.meta.env` token at build time. Aliasing
`import.meta` and reading `meta['env']` defeated that replacement, so at runtime
`import.meta.env` was `undefined`. `readEnv()` then threw a `TypeError` on the first
access (`VITE_USE_MOCK`). That throw propagated through `useApi()` → `auth.init()`
and was swallowed by `App.vue`'s `onMounted` try/catch — leaving the splash up with
**no network `/mcp` request and no console error**. The backend was fine throughout.

## Fix

`readEnv()` now references `import.meta.env` directly (and guards with `?.`), so Vite
inlines the env object and runtime lookups succeed:

```ts
export function readEnv(key: string): string | undefined {
  const bag = import.meta.env as unknown as ViteEnvBag
  return bag?.[key]
}
```

Verified: the production bundle no longer contains a bare `=import.meta.env`; the env
object is inlined (`PROD:!0`), so the SPA initializes, calls `/mcp` (`wake_up`), and
loads.

## Upgrade

No schema changes (still at V0030). UI-only rebuild. Drop-in over 9.2.0.
