import { BrowserRouter, Navigate, Route, Routes } from 'react-router-dom'
import { Layout } from './components/layout/Layout'
import { SpreadsPage } from './pages/SpreadsPage'
import { AccountPage } from './pages/AccountPage'
import { ScannerPage } from './pages/ScannerPage'

function App() {
  return (
    <BrowserRouter>
      <Routes>
        <Route element={<Layout />}>
          <Route index element={<Navigate to="/spreads" replace />} />
          <Route path="/spreads" element={<SpreadsPage />} />
          <Route path="/account" element={<AccountPage />} />
          <Route path="/scanner" element={<ScannerPage />} />
        </Route>
      </Routes>
    </BrowserRouter>
  )
}

export default App
