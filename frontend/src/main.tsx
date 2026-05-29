import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import './index.css'
import App from './App.tsx'
import { client as healthClient } from './generated/health/client.gen'
import { client as accountClient } from './generated/account/client.gen'
import { client as spreadsClient } from './generated/spreads/client.gen'
import { client as diagnosticClient } from './generated/diagnostic/client.gen'
import { client as universeClient } from './generated/universe/client.gen'
import { client as flagsClient } from './generated/flags/client.gen'

healthClient.setConfig({ baseUrl: '/api' })
accountClient.setConfig({ baseUrl: '/api' })
spreadsClient.setConfig({ baseUrl: '/api' })
diagnosticClient.setConfig({ baseUrl: '/api' })
universeClient.setConfig({ baseUrl: '/api' })
flagsClient.setConfig({ baseUrl: '/api' })

const queryClient = new QueryClient()

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <QueryClientProvider client={queryClient}>
      <App />
    </QueryClientProvider>
  </StrictMode>,
)
