<script setup lang="ts">
// A token's permissions as compact chips: the label, the full explanation on hover (title) or, as touch screens have
// no tooltips, on a tap (spelled out below the chips).
import { ref } from 'vue'
import { t } from '../i18n'
import type { Scope } from '../cloud'

defineProps<{ scopes: Scope[] }>()
const open = ref<Scope | null>(null)
</script>

<template>
  <span class="scopechips">
    <button v-for="s in scopes" :key="s" type="button" class="chip scope" :class="'s-' + s" :title="t('scopeHelp_' + s)"
      :aria-expanded="open === s" @click="open = open === s ? null : s">{{ t('scope_' + s) }}</button>
    <span v-if="!scopes.length" class="muted">{{ t('scopesNone') }}</span>
    <span v-if="open && scopes.includes(open)" class="scopetip small">{{ t('scope_' + open) }}: {{ t('scopeHelp_' + open) }}</span>
  </span>
</template>
