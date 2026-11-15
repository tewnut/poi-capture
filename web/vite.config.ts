import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

// base './': the built files work from any folder (any host path, or a local copy)
export default defineConfig({ base: './', plugins: [vue()] })
