import { defineConfig, mergeConfig } from 'vite'
import configuration from './vite.config'

// Keep the normal distributed plugins, pages and output contracts.
export default defineConfig(async environment => {
  const existing =
    typeof configuration === 'function' ? await configuration(environment) : await configuration
  return mergeConfig(existing, {
    css: { preprocessorMaxWorkers: 1 },
    build: { rollupOptions: { maxParallelFileOps: 16 } }
  })
})
