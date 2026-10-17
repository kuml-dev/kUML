import { defineConfig } from 'vite';

export default defineConfig({
  build: {
    outDir: 'src/main/resources/web/static/assets',
    emptyOutDir: true,
    rollupOptions: {
      input: 'frontend/app.js',
      output: {
        entryFileNames: 'app.js',
        chunkFileNames: 'chunks/[name]-[hash].js',
      },
    },
  },
  resolve: {
    dedupe: [
      '@codemirror/state',
      '@codemirror/view',
    ],
  },
});
