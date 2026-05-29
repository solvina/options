import { BrowserRouter, Navigate, Route, Routes } from 'react-router-dom'
import { Layout } from './components/layout/Layout'
import { SpreadsPage } from './pages/SpreadsPage'
import { AccountPage } from './pages/AccountPage'
import { ScannerPage } from './pages/ScannerPage'
import { DiagnosticPage } from './pages/DiagnosticPage'
import { UniversePage } from './pages/UniversePage'
import { AnalyticsPage } from './pages/AnalyticsPage'
import { FlagsPage } from './pages/FlagsPage'
import { FlagAnalyticsPage } from './pages/FlagAnalyticsPage'

function App() {
  return (
    <BrowserRouter>
      <Routes>
        <Route element={<Layout />}>
          <Route index element={<Navigate to="/spreads" replace />} />
          <Route path="/spreads" element={<SpreadsPage />} />
          <Route path="/account" element={<AccountPage />} />
          <Route path="/scanner" element={<ScannerPage />} />
          <Route path="/analytics" element={<AnalyticsPage />} />
          <Route path="/universe" element={<UniversePage />} />
          <Route path="/diagnostic" element={<DiagnosticPage />} />
          <Route path="/flags" element={<FlagsPage />} />
          <Route path="/flags/analytics" element={<FlagAnalyticsPage />} />
        </Route>
      </Routes>
    </BrowserRouter>
  )
}

export default App
