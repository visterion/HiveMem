type ViteEnvBag = Record<string, string | undefined>

const meta = import.meta as unknown as { [k: string]: ViteEnvBag }
const BAG: ViteEnvBag = meta['env']

export function readEnv(key: string): string | undefined {
  return BAG[key]
}
