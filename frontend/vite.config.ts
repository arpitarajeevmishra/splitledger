import react from '@vitejs/plugin-react'
import tailwindcss from '@tailwindcss/vite'
import { defineConfig } from 'vitest/config'

export default defineConfig({
  plugins: [react(), tailwindcss()],
  server: {
    port: 5173,
    // The SPA calls relative /api/v1 paths in every environment (finding C13):
    // in production Caddy serves both on one origin, which is also what the
    // SameSite=Strict refresh cookie requires (ADR-011). Locally this proxy
    // stands in for Caddy, so no base-URL env var is ever needed.
    proxy: {
      '/api': { target: 'http://localhost:8080', changeOrigin: true },
    },
  },
  test: {
    environment: 'jsdom',
    setupFiles: ['./src/test/setup.ts'],
    css: true,
    coverage: { provider: 'v8', reporter: ['text', 'lcov'] },
  },
})
