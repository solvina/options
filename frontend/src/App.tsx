import { BrowserRouter, Navigate, Route, Routes } from 'react-router-dom'
import { Layout } from './components/layout/Layout'
import { SpreadsPage } from './pages/SpreadsPage'
import { BearCallsPage } from './pages/BearCallsPage'
import { DashboardPage } from './pages/DashboardPage'
import { AccountPage } from './pages/AccountPage'
import { ScannerPage } from './pages/ScannerPage'
import { DiagnosticPage } from './pages/DiagnosticPage'
import { UniversePage } from './pages/UniversePage'
import { AnalyticsPage } from './pages/AnalyticsPage'
import { FlagsPage } from './pages/FlagsPage'
import { FlagAnalyticsPage } from './pages/FlagAnalyticsPage'
import { HistoricalDataPage } from './pages/HistoricalDataPage'
import { BacktestPage } from './pages/BacktestPage'
import { StockBacktestPage } from './pages/StockBacktestPage'
import { ReportsPage } from './pages/ReportsPage'
import { ChangelogPage } from './pages/ChangelogPage'

function App() {
  return (
    <BrowserRouter>
      <Routes>
        <Route element={<Layout />}>
          <Route index element={<Navigate to="/dashboard" replace />} />
          <Route path="/dashboard" element={<DashboardPage />} />
          <Route path="/reports" element={<ReportsPage />} />
          <Route path="/spreads" element={<Navigate to="/spreads/positions" replace />} />
          <Route path="/spreads/positions" element={<SpreadsPage />} />
          <Route path="/spreads/analytics" element={<AnalyticsPage />} />
          <Route path="/bear-calls/positions" element={<BearCallsPage />} />
          <Route path="/bear-calls" element={<Navigate to="/bear-calls/positions" replace />} />
          <Route path="/account" element={<AccountPage />} />
          <Route path="/scanner" element={<ScannerPage />} />
          <Route path="/universe" element={<UniversePage />} />
          <Route path="/diagnostic" element={<DiagnosticPage />} />
          <Route path="/flags/positions" element={<FlagsPage />} />
          <Route path="/flags" element={<Navigate to="/flags/positions" replace />} />
          <Route path="/flags/analytics" element={<FlagAnalyticsPage />} />
          <Route path="/historical" element={<HistoricalDataPage />} />
          <Route path="/changelog" element={<ChangelogPage />} />
          <Route path="/backtest" element={<BacktestPage />} />
          <Route path="/backtest/stock" element={<StockBacktestPage />} />
        </Route>
      </Routes>
    </BrowserRouter>
  )
}

export default App
