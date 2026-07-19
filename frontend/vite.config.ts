import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'
import tailwindcss from '@tailwindcss/vite'
import path from 'path'

export default defineConfig({
  plugins: [react(), tailwindcss()],
  resolve: {
    alias: {
      '@': path.resolve(__dirname, './src'),
    },
  },
  server: {
    port: 3001,
    proxy: {
      '/api': {
        // Default: dev engine on 8081. Point the UI at the local backtest instance with
        //   VITE_API_TARGET=http://localhost:8082 npm run dev
        target: process.env.VITE_API_TARGET ?? 'http://localhost:8081',
        changeOrigin: true,
        rewrite: (path) => path.replace(/^\/api/, '/options'),
      },
    },
  },
})
