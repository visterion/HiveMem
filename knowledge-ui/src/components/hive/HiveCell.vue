<script setup lang="ts">
import { computed, onBeforeUnmount, shallowRef, watch } from 'vue'
import * as THREE from 'three'
import type { GoldbergCell } from '../../composables/goldbergMath'
import type { RealmPalette } from '../../composables/realmPalette'

const props = defineProps<{
  cell: GoldbergCell
  realmName: string | null
  palette: RealmPalette | null
  isAccentPink?: boolean
  isAccentViolet?: boolean
}>()

const R_SPHERE = 3

function rng(seed: number): () => number {
  let s = seed >>> 0
  return () => {
    s = (s * 1664525 + 1013904223) & 0xffffffff
    return ((s >>> 0) / 0xffffffff)
  }
}

const hueShift = computed(() => {
  const r = rng(props.cell.index + 17)
  return (r() * 30) - 15
})

const latY = computed(() => props.cell.centroid[1] / R_SPHERE)

function shiftColor(input: string, hueDelta: number, lightDelta: number): string {
  let h = 0, s = 0, l = 0
  if (input.startsWith('hsl')) {
    const match = input.match(/hsl\(([\d.]+),\s*([\d.]+)%,\s*([\d.]+)%/)
    if (match) { h = Number(match[1]); s = Number(match[2]) / 100; l = Number(match[3]) / 100 }
  } else {
    const c = input.replace('#', '')
    const n = parseInt(c.length === 3 ? c.split('').map((x) => x + x).join('') : c, 16)
    const r = ((n >> 16) & 255) / 255, g = ((n >> 8) & 255) / 255, b = (n & 255) / 255
    const max = Math.max(r, g, b), min = Math.min(r, g, b)
    l = (max + min) / 2
    if (max !== min) {
      const d = max - min
      s = l > 0.5 ? d / (2 - max - min) : d / (max + min)
      switch (max) {
        case r: h = ((g - b) / d + (g < b ? 6 : 0)); break
        case g: h = (b - r) / d + 2; break
        case b: h = (r - g) / d + 4; break
      }
      h *= 60
    }
  }
  const nH = ((h + hueDelta) % 360 + 360) % 360
  const nS = Math.max(0, Math.min(1, s))
  const nL = Math.max(0.02, Math.min(0.98, l + lightDelta))
  return `hsl(${nH.toFixed(1)}, ${(nS * 100).toFixed(1)}%, ${(nL * 100).toFixed(1)}%)`
}

function hslToHex(h: number, s: number, l: number): string {
  const a = s * Math.min(l, 1 - l)
  const f = (n: number) => {
    const k = (n + h / 30) % 12
    const colour = l - a * Math.max(-1, Math.min(k - 3, 9 - k, 1))
    return Math.round(colour * 255).toString(16).padStart(2, '0')
  }
  return `#${f(0)}${f(8)}${f(4)}`
}

function hexToRgba(input: string, alpha: number): string {
  if (input.startsWith('hsl')) {
    const match = input.match(/hsl\(([\d.]+),\s*([\d.]+)%,\s*([\d.]+)%/)
    if (match) {
      const h = Number(match[1]), s = Number(match[2]) / 100, l = Number(match[3]) / 100
      const hex = hslToHex(h, s, l)
      return hexToRgba(hex, alpha)
    }
  }
  const c = input.replace('#', '')
  const n = parseInt(c.length === 3 ? c.split('').map((x) => x + x).join('') : c, 16)
  const r = (n >> 16) & 255, g = (n >> 8) & 255, b = n & 255
  return `rgba(${r},${g},${b},${alpha})`
}

function buildCellCanvas(): HTMLCanvasElement {
  const S = 256
  const canvas = document.createElement('canvas')
  canvas.width = S; canvas.height = S
  const ctx = canvas.getContext('2d')!

  let glow: string, base: string, tint: string
  if (props.isAccentPink) {
    glow = '#FFB8CF'; base = '#FF6B9D'; tint = '#6e2450'
  } else if (props.isAccentViolet) {
    glow = '#c9b8ff'; base = '#9a6bff'; tint = '#3e2a70'
  } else if (props.palette) {
    const shift = hueShift.value + 25 * -latY.value
    const lShift = 0.1 * latY.value
    glow = shiftColor(props.palette.glow, shift, lShift)
    base = shiftColor(props.palette.base, shift, lShift)
    tint = shiftColor(props.palette.tint, shift, lShift)
  } else {
    glow = '#2a2a2a'; base = '#1a1a1a'; tint = '#0d0d0d'
  }

  const grad = ctx.createRadialGradient(S / 2, S / 2, 0, S / 2, S / 2, S / 2)
  grad.addColorStop(0, glow)
  grad.addColorStop(0.45, base)
  grad.addColorStop(1, tint)
  ctx.fillStyle = grad
  ctx.fillRect(0, 0, S, S)

  const r = rng(props.cell.index + 42)
  const shapeCount = 2 + Math.floor(r() * 4)
  for (let i = 0; i < shapeCount; i++) {
    const x = r() * S, y = r() * S, w = 8 + r() * 60, h = 8 + r() * 60
    const alpha = 0.08 + r() * 0.07
    ctx.fillStyle = hexToRgba(base, alpha)
    if (r() < 0.5) {
      ctx.fillRect(x, y, w, h)
    } else {
      ctx.beginPath(); ctx.arc(x, y, Math.min(w, h) / 2, 0, Math.PI * 2); ctx.fill()
    }
  }

  return canvas
}

const geometry = shallowRef<THREE.BufferGeometry | null>(null)
const edgesGeometry = shallowRef<THREE.BufferGeometry | null>(null)
const tex = shallowRef<THREE.CanvasTexture | null>(null)

function buildGeometry() {
  const centroid = new THREE.Vector3(...props.cell.centroid)
  const normal = centroid.clone().normalize()
  const N = props.cell.vertices.length
  const INSET = 0.88
  const vertsRel = props.cell.vertices.map((v) => {
    const raw = new THREE.Vector3(...v).sub(centroid)
    return raw.multiplyScalar(INSET)
  })

  const vRef = vertsRel[0].clone().normalize()
  const uBasis = vRef.clone().sub(normal.clone().multiplyScalar(vRef.dot(normal))).normalize()
  const vBasis = new THREE.Vector3().crossVectors(normal, uBasis).normalize()

  const frontDepth = 0.04
  const backDepth = 0.10
  const positions: number[] = []
  const uvs: number[] = []

  for (const v of vertsRel) {
    const front = v.clone().add(normal.clone().multiplyScalar(frontDepth))
    positions.push(front.x, front.y, front.z)
    const u = v.dot(uBasis), w = v.dot(vBasis)
    const r = Math.sqrt(u * u + w * w) || 1
    uvs.push(0.5 + (u / r) * 0.5, 0.5 + (w / r) * 0.5)
  }
  for (const v of vertsRel) {
    const back = v.clone().sub(normal.clone().multiplyScalar(backDepth))
    positions.push(back.x, back.y, back.z)
    const u = v.dot(uBasis), w = v.dot(vBasis)
    const r = Math.sqrt(u * u + w * w) || 1
    uvs.push(0.5 + (u / r) * 0.5, 0.5 + (w / r) * 0.5)
  }

  const indices: number[] = []
  for (let i = 1; i < N - 1; i++) indices.push(0, i, i + 1)
  for (let i = 1; i < N - 1; i++) indices.push(N, N + i + 1, N + i)
  for (let i = 0; i < N; i++) {
    const j = (i + 1) % N
    indices.push(i, j, N + i)
    indices.push(j, N + j, N + i)
  }

  const geo = new THREE.BufferGeometry()
  geo.setAttribute('position', new THREE.Float32BufferAttribute(positions, 3))
  geo.setAttribute('uv', new THREE.Float32BufferAttribute(uvs, 2))
  geo.setIndex(indices)
  geo.computeVertexNormals()
  geometry.value?.dispose() // free the GPU buffers of the replaced geometry
  geometry.value = geo

  const edgePos: number[] = []
  for (let i = 0; i < N; i++) {
    const a = vertsRel[i]
    const b = vertsRel[(i + 1) % N]
    edgePos.push(a.x, a.y, a.z, b.x, b.y, b.z)
  }
  const edges = new THREE.BufferGeometry()
  edges.setAttribute('position', new THREE.Float32BufferAttribute(edgePos, 3))
  edgesGeometry.value?.dispose()
  edgesGeometry.value = edges

  if (tex.value) tex.value.dispose()
  const t = new THREE.CanvasTexture(buildCellCanvas())
  t.needsUpdate = true
  tex.value = t
}

watch(() => props.cell.index, buildGeometry, { immediate: true })
watch(() => [props.realmName, props.isAccentPink, props.isAccentViolet], buildGeometry)

onBeforeUnmount(() => {
  if (tex.value) tex.value.dispose()
  geometry.value?.dispose()
  edgesGeometry.value?.dispose()
  edgeMaterial.dispose()
})

// Local hover state only -- no cross-cell coordination in cinema view.
const hovered = shallowRef(false)
const intensity = computed(() => (hovered.value && props.realmName ? 1.4 : 0.75))
const scale = computed(() => (hovered.value && props.realmName ? 1.03 : 1))

function onPointerOver(e: any) {
  e.stopPropagation?.()
  hovered.value = true
}
function onPointerLeave(e: any) {
  e.stopPropagation?.()
  hovered.value = false
}

const groupPos = computed<[number, number, number]>(() => props.cell.centroid)

// Single shared edge material per cell (disposed on unmount) — creating a new
// material on every rebuild leaked GPU programs.
const edgeMaterial = new THREE.LineBasicMaterial({ color: '#2a2a2a' })

// Pre-build LineSegments object so the template stays declarative.
const edgeObject = computed(() => {
  if (!edgesGeometry.value) return null
  return new THREE.LineSegments(edgesGeometry.value, edgeMaterial)
})
</script>

<template>
  <TresGroup
    :position="groupPos"
    :scale="scale"
    @pointer-over="onPointerOver"
    @pointer-leave="onPointerLeave"
  >
    <TresMesh :geometry="geometry ?? undefined" :visible="geometry !== null">
      <TresMeshPhysicalMaterial
        :color="'#ffffff'"
        :map="tex"
        :emissive-map="tex"
        :metalness="0.55"
        :roughness="0.25"
        :transmission="0"
        :ior="1.3"
        :emissive="'#ffffff'"
        :emissive-intensity="intensity"
        :transparent="false"
      />
    </TresMesh>

    <primitive v-if="edgeObject" :object="edgeObject" />
  </TresGroup>
</template>
