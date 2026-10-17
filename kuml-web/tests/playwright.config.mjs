import { defineConfig } from '@playwright/test';

export default defineConfig({
  testDir: '.',
  testMatch: 'editor.spec.mjs',
  outputDir: '/output/playwright-test-results',
  reporter: [
    ['line'],
    ['html', { outputFolder: '/output/playwright-report', open: 'never' }],
  ],
  use: {
    baseURL: process.env.KUML_WEB_URL ?? 'http://127.0.0.1:8080',
  },
});
