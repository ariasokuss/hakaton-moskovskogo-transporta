import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

// В разработке API проксируется на бэкенд, в Docker — через nginx.
export default defineConfig({
  plugins: [react()],
  server: { host: '127.0.0.1', port: 5173, proxy: { '/api': 'http://127.0.0.1:8080' } },
})
