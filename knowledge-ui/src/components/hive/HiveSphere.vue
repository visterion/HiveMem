<script setup lang="ts">
import { computed, onMounted, shallowRef, watch } from 'vue'
import { buildGoldbergCells, assignRealms } from '../../composables/goldbergMath'
import type { GoldbergCell } from '../../composables/goldbergMath'
import { paletteForRealm, type RealmPalette } from '../../composables/realmPalette'
import { NO_REALM } from '../../composables/realmMeta'
import type { Realm, Cell } from '../../api/types'
import HiveCell from './HiveCell.vue'

const props = defineProps<{ realms: Realm[]; cells: Cell[] }>()

const R = 3

const goldbergCells = shallowRef<GoldbergCell[]>([])
const realmAssignment = shallowRef<Map<number, string | null>>(new Map())
const palettes = shallowRef<Map<string, RealmPalette>>(new Map())

function rebuild() {
  goldbergCells.value = buildGoldbergCells(R, 1)
  // Use cells array as authoritative count if realms have none, else use realm.cell_count.
  const counts = new Map<string, number>()
  for (const c of props.cells) {
    const realm = c.realm ?? NO_REALM
    counts.set(realm, (counts.get(realm) ?? 0) + 1)
  }
  const realmInput = props.realms.map((r) => ({
    name: r.name,
    cellCount: r.cell_count || counts.get(r.name) || 0,
  }))
  realmAssignment.value = assignRealms(goldbergCells.value, realmInput)
  const pmap = new Map<string, RealmPalette>()
  props.realms.forEach((r, i) => pmap.set(r.name, paletteForRealm(i)))
  palettes.value = pmap
}

onMounted(rebuild)
// Rebuild when data arrives after mount (realms/cells stream in progressively).
watch(() => [props.realms, props.cells], rebuild)

const cellList = computed(() => {
  return goldbergCells.value.map((c) => {
    const rn = realmAssignment.value.get(c.index) ?? null
    const pal = rn ? palettes.value.get(rn) ?? null : null
    const isPink = (c.index % 13) === 0 && rn !== null
    const isViolet = !isPink && (c.index % 17) === 0 && rn !== null
    return { cell: c, realmName: rn, palette: pal, isAccentPink: isPink, isAccentViolet: isViolet }
  })
})
</script>

<template>
  <TresGroup>
    <HiveCell
      v-for="item in cellList"
      :key="item.cell.index"
      :cell="item.cell"
      :realm-name="item.realmName"
      :palette="item.palette"
      :is-accent-pink="item.isAccentPink"
      :is-accent-violet="item.isAccentViolet"
    />
  </TresGroup>
</template>
