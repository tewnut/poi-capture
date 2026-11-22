import { cloudflareTest } from '@cloudflare/vitest-plugin'
import { defineConfig } from 'vitest/config'

// Tests run inside workerd (Miniflare) with the bindings from wrangler.jsonc: a local R2 bucket and Durable Object.
export default defineConfig({
  test: { testTimeout: 30_000 },   // the multi-part uploads (19 MiB) take seconds on a busy machine
  plugins: [cloudflareTest({
    wrangler: { configPath: './wrangler.jsonc' },
    miniflare: { bindings: { TOKEN: 'test-token', ADMIN_TOKEN: 'admin-token', R2_ACCESS_KEY_ID: 'test-key-id', R2_SECRET_ACCESS_KEY: 'test-secret', CORS_ORIGINS: 'http://localhost:5173,https://*.poi-capture-reviewer.pages.dev' } },
  })],
})
