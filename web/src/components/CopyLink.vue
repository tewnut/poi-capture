<script setup lang="ts">
import { ref } from 'vue'

const props = defineProps<{ url: string; label?: string }>()
const done = ref(false)
async function copy() {
  try {
    await navigator.clipboard.writeText(props.url)
  } catch {
    window.prompt('Copy this link', props.url)   // clipboard blocked (http, old browser)
  }
  done.value = true
  setTimeout(() => (done.value = false), 1500)
}
</script>

<template>
  <button class="ghost link" :title="url" @click="copy">{{ done ? 'Copied ✓' : label || 'Copy link' }}</button>
</template>

<style scoped>
.link { font-size: 12px; color: var(--accent); white-space: nowrap; }
</style>
