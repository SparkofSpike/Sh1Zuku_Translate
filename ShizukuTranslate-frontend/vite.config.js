import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import { cpSync, rmSync } from 'node:fs'
import { fileURLToPath, URL } from 'node:url'
import { execSync } from 'node:child_process'

function syncBackendStatic() {
  return {
    name: 'sync-backend-static',
    apply: 'build',
    closeBundle() {
      const distDir = fileURLToPath(new URL('./dist/', import.meta.url))
      const staticDir = fileURLToPath(new URL('../ShizukuTranslate/src/main/resources/static/', import.meta.url))
      rmSync(staticDir, { recursive: true, force: true })
      cpSync(distDir, staticDir, { recursive: true })
    }
  }
}

// Injected at build time for the About page (build date + git commit).
function gitCommit() {
  try {
    return execSync('git rev-parse --short HEAD', { encoding: 'utf8' }).trim()
  } catch {
    return 'unknown'
  }
}

export default defineConfig({
  plugins: [vue(), syncBackendStatic()],
  define: {
    'import.meta.env.VITE_BUILD_TIME': JSON.stringify(Date.now()),
    'import.meta.env.VITE_COMMIT': JSON.stringify(gitCommit())
  },
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url))
    }
  },
  server: {
    port: 5173
  }
})
