<script setup lang="ts">
import AppSidebar from './components/AppSidebar.vue'
import SettingsModal from './components/SettingsModal.vue'
import ToastHost from './components/ui/ToastHost.vue'
import { settingsOpen, sidebarOpen } from './composables/useUi'
</script>

<template>
  <div class="flex h-full overflow-hidden">
    <AppSidebar v-show="sidebarOpen" />

    <main class="flex min-w-0 flex-1 flex-col overflow-hidden">
      <RouterView v-slot="{ Component }">
        <Transition name="fade" mode="out-in">
          <component :is="Component" />
        </Transition>
      </RouterView>
    </main>

    <ToastHost />
    <SettingsModal :open="settingsOpen" @close="settingsOpen = false" />
  </div>
</template>
