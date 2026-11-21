import { cloudflareTest } from '@cloudflare/vitest-plugin'
import { defineConfig } from 'vitest/config'

// Tests run inside workerd (Miniflare) with the bindings from wrangler.jsonc: a local R2 bucket and Durable Object.
export default defineConfig({
  plugins: [cloudflareTest({
    wrangler: { configPath: './wrangler.jsonc' },
    miniflare: { bindings: { TOKEN: 'test-token', ADMIN_TOKEN: 'admin-token', CORS_ORIGINS: 'http://localhost:5173,https://*.poi-capture-reviewer.pages.dev' } },
  })],
})
