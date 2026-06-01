import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';
import path from 'node:path';

export default defineConfig({
  plugins: [react()],
  base: '/assets/',
  build: {
    outDir: path.resolve(__dirname, '../resources/static'),
    emptyOutDir: true,
    cssCodeSplit: false,
    rollupOptions: {
      input: path.resolve(__dirname, 'src/main.tsx'),
      output: {
        entryFileNames: 'pulse-hosts.js',
        assetFileNames: 'pulse-hosts.css'
      }
    }
  }
});
