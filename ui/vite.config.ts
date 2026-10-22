/// <reference types="vitest/config" />
import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

// The dev server must run on 5173: that origin is the one the APIs allow (CORS) and Keycloak redirects back to.
export default defineConfig({
  plugins: [react()],
  server: { port: 5173, strictPort: true },
  test: {
    environment: 'jsdom',
    setupFiles: ['./src/test/setup.ts'],
    include: ['src/**/*.test.{ts,tsx}'],   // e2e/ holds the browser tests, which Playwright runs
    css: false,
  },
});
