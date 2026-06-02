<script setup lang="ts">
import { onMounted, onUnmounted, ref } from 'vue'
import { useQueenStore } from '../stores/queen'

const store = useQueenStore()
const drawer = ref(false)
let timer: number | null = null

function fmtCost(micros: number | null) {
  return micros == null ? '—' : `$${(micros / 1_000_000).toFixed(4)}`
}
function fmtDuration(ms: number | null) {
  return ms == null ? '—' : `${(ms / 1000).toFixed(1)}s`
}
async function openRun(id: string) {
  await store.selectRun(id)
  drawer.value = true
}

onMounted(async () => {
  await store.refresh()
  timer = window.setInterval(() => store.refresh(), 10_000)
})
onUnmounted(() => { if (timer) clearInterval(timer) })
</script>

<template>
  <v-container fluid class="pa-4">
    <h2 class="text-h6 mb-2">Queen activity</h2>
    <v-alert v-if="store.unavailable" type="warning" variant="tonal" class="mb-3">
      Vistierie nicht erreichbar — die Queen ist evtl. deaktiviert.
    </v-alert>

    <v-table density="comfortable" class="mb-6">
      <thead>
        <tr>
          <th>Started</th><th>Agent</th><th>Trigger</th><th>Status</th><th>Duration</th>
          <th v-if="store.costAvailable">LLM calls</th>
          <th v-if="store.costAvailable">Cost</th>
        </tr>
      </thead>
      <tbody>
        <tr v-for="r in store.runs" :key="r.id" style="cursor: pointer" @click="openRun(r.id)">
          <td>{{ r.startedAt ?? '—' }}</td>
          <td>{{ r.agent }}</td>
          <td>{{ r.trigger ?? '—' }}</td>
          <td>
            <v-chip size="small" :color="r.status === 'done' ? 'success' : r.status === 'failed' ? 'error' : 'warning'">
              {{ r.status }}
            </v-chip>
          </td>
          <td>{{ fmtDuration(r.durationMs) }}</td>
          <td v-if="store.costAvailable">{{ r.llmCalls ?? '—' }}</td>
          <td v-if="store.costAvailable">{{ fmtCost(r.costMicros) }}</td>
        </tr>
        <tr v-if="store.runs.length === 0">
          <td :colspan="store.costAvailable ? 7 : 5" class="text-medium-emphasis">No runs yet.</td>
        </tr>
      </tbody>
    </v-table>

    <h2 class="text-h6 mb-2">Pending proposals</h2>
    <v-list v-if="store.pending.length" lines="two">
      <v-list-item v-for="p in store.pending" :key="p.id" :title="p.description ?? p.id" :subtitle="p.realm ?? ''">
        <template #append>
          <v-btn size="small" color="success" variant="tonal" class="mr-2" @click="store.approve(p.id, true)">Accept</v-btn>
          <v-btn size="small" color="error" variant="tonal" @click="store.approve(p.id, false)">Reject</v-btn>
        </template>
      </v-list-item>
    </v-list>
    <div v-else class="text-medium-emphasis">No pending Queen proposals.</div>

    <v-navigation-drawer v-model="drawer" location="right" temporary width="420">
      <div class="pa-4" v-if="store.selectedRun">
        <h3 class="text-subtitle-1 mb-2">Run {{ (store.selectedRun.run as any).id }}</h3>
        <div class="mb-3 text-body-2">{{ (store.selectedRun.run as any).summary ?? '' }}</div>
        <v-divider class="mb-2" />
        <div class="text-overline">Events</div>
        <v-timeline density="compact" side="end">
          <v-timeline-item v-for="(e, i) in store.selectedRun.events" :key="i" size="x-small">
            <div class="text-body-2"><strong>{{ e.type }}</strong> <span class="text-medium-emphasis">{{ (e as any).at ?? '' }}</span></div>
          </v-timeline-item>
        </v-timeline>
      </div>
    </v-navigation-drawer>
  </v-container>
</template>
