<script setup lang="ts">
// A token's permissions as four checkboxes, each with its one-line explanation (server/README.md, "Tokens and
// permissions"); with `presets`, buttons that set the boxes (Phone, Reviewer, Agent). Used to issue and to edit.
import { ref } from 'vue'
import { t } from '../i18n'
import { PRESETS, SCOPES, sortScopes, type Scope } from '../cloud'

defineProps<{ modelValue: Scope[]; presets?: boolean; disabled?: boolean }>()
const emit = defineEmits<{ 'update:modelValue': [Scope[]] }>()

const presetKeys = Object.keys(PRESETS) as (keyof typeof PRESETS)[]
const same = (a: readonly Scope[], b: readonly Scope[]) => a.length === b.length && a.every((s) => b.includes(s))
// the boxes as they stand on screen (not the last rendered model): two quick clicks before a re-render both count
const boxes = ref<HTMLElement | null>(null)
function changed() {
  const on = [...(boxes.value?.querySelectorAll<HTMLInputElement>('input[type=checkbox]') ?? [])].filter((i) => i.checked).map((i) => i.value)
  emit('update:modelValue', sortScopes(on))
}
</script>

<template>
  <div class="scopepick">
    <div v-if="presets" class="presets small" role="group" :aria-label="t('preset')">
      <span class="muted">{{ t('preset') }}:</span>
      <button v-for="p in presetKeys" :key="p" type="button" class="secondary small" :class="{ on: same(modelValue, PRESETS[p]) }"
        :aria-pressed="same(modelValue, PRESETS[p])" :disabled="disabled" @click="emit('update:modelValue', [...PRESETS[p]])">{{ t('preset_' + p) }}</button>
    </div>
    <div ref="boxes" class="scopeboxes">
      <label v-for="s in SCOPES" :key="s" class="scopebox" :class="{ on: modelValue.includes(s) }">
        <input type="checkbox" :value="s" :checked="modelValue.includes(s)" :disabled="disabled" @change="changed" />
        <span><b>{{ t('scope_' + s) }}</b><span class="muted">{{ t('scopeHelp_' + s) }}</span></span>
      </label>
    </div>
    <p v-if="!modelValue.length" class="small err-text">{{ t('scopesPick') }}</p>
  </div>
</template>
