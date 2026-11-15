<script setup lang="ts">
import { ref, watchEffect } from 'vue'
import { photoUrl } from '../store'
import { t } from '../i18n'

const props = defineProps<{ ds: string; path?: string | null; alt?: string }>()
const url = ref<string | null>(null)
watchEffect(async () => { url.value = await photoUrl(props.ds, props.path) })
</script>

<template>
  <img v-if="url" :src="url" :alt="alt || ''" loading="lazy" />
  <div v-else class="nophoto">{{ t('noPhoto') }}</div>
</template>

<style scoped>
.nophoto { display: grid; place-items: center; background: var(--canvas); color: var(--muted); font-size: 12px; min-height: 60px; border-radius: 8px; }
</style>
