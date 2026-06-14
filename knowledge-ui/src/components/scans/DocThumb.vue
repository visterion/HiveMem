<script setup lang="ts">
import type { DocumentRow } from '../../api/types'
import HmIcon from '../shell/HmIcon.vue'
const props = defineProps<{ d: DocumentRow; big?: boolean }>()
const thumbUrl = (d: DocumentRow) => d.has_thumbnail && d.attachment_id ? `/api/attachments/${d.attachment_id}/thumbnail` : null
</script>
<template>
  <div :class="['docthumb', { big }]">
    <img v-if="thumbUrl(d)" class="dt-img" :src="thumbUrl(d)!" alt="" loading="lazy" />
    <div v-else class="dt-paper">
      <div class="dt-header" />
      <div class="dt-lines">
        <span v-for="i in (big ? 12 : 6)" :key="i" :style="{ width: (94 - ((i*7)%40)) + '%', opacity: i===1 ? 0.9 : 0.5 }" />
      </div>
    </div>
    <span v-if="(d.page_count ?? 0) > 1" class="dt-pages"><HmIcon name="pages" :size="11" /> {{ d.page_count }}</span>
    <span :class="['dt-status', d.status]" :title="d.status" />
  </div>
</template>
<style scoped>
.docthumb { position:relative; height:124px; background:var(--bg-0); border-radius:9px; border:1px solid var(--line); display:grid; place-items:center; overflow:hidden; }
.docthumb.big { height:100%; }
/* Document-style preview: pin the page to the top of the tile at full width so the
   header/letterhead is what shows — not a centred, cropped, zoomed-in slice.
   Absolute (out of grid flow) so the fixed tile height controls the size; otherwise
   the image's intrinsic height grows the grid row and the page renders oversized. */
.dt-img { position:absolute; inset:0; width:100%; height:100%; object-fit:cover; object-position:center top; background:#f7f4ed; }
.dt-paper { width:108px; height:138px; background:#f7f4ed; border-radius:4px; overflow:hidden; box-shadow:0 8px 20px -8px rgba(0,0,0,.55); display:flex; flex-direction:column; }
.dt-header { height:26px; flex:none; opacity:.9; background:var(--honey-deep); }
.dt-lines { padding:10px 11px; display:flex; flex-direction:column; gap:5px; position:relative; flex:1; }
.dt-lines span { height:4px; background:#cfc7b4; border-radius:2px; }
.dt-pages { position:absolute; top:9px; right:9px; display:inline-flex; align-items:center; gap:4px; font-size:10.5px; background:rgba(0,0,0,.6); color:#fff; padding:2px 7px; border-radius:6px; }
.dt-status { position:absolute; bottom:9px; left:9px; width:9px; height:9px; border-radius:50%; }
.dt-status.committed { background:var(--good); box-shadow:0 0 7px var(--good); }
.dt-status.pending { background:var(--honey); box-shadow:0 0 7px var(--honey); }
</style>
