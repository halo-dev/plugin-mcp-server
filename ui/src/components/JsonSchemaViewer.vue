<script setup lang="ts">
import { computed } from 'vue'

const props = defineProps<{
  title: string
  schema?: Record<string, unknown>
  emptyMessage: string
}>()

const formattedSchema = computed(() =>
  props.schema ? JSON.stringify(props.schema, null, 2) : undefined,
)
</script>

<template>
  <section>
    <h3 class=":uno: mb-2 text-sm text-gray-700 font-medium">{{ title }}</h3>
    <div v-if="formattedSchema" class="w-full rounded-md overflow-hidden">
      <VCodemirror :model-value="formattedSchema" language="json" class="w-full" />
    </div>
    <p v-else class=":uno: rounded-md bg-gray-50 px-4 py-3 text-sm text-gray-500">
      {{ emptyMessage }}
    </p>
  </section>
</template>
