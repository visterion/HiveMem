type ViteEnvBag = Record<string, string | undefined>

export function readEnv(key: string): string | undefined {
  // Reference `import.meta.env` directly so Vite statically inlines the env
  // object at build time. The previous indirection (aliasing import.meta and
  // reading `meta['env']`) defeated that replacement, leaving import.meta.env
  // undefined at runtime — readEnv then threw a TypeError, which useApi()/
  // auth.init() propagated and App.vue swallowed, hanging the UI on "Connecting…".
  const bag = import.meta.env as unknown as ViteEnvBag
  return bag?.[key]
}
