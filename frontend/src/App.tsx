import { BrowserRouter, Route, Routes } from 'react-router-dom'

function App() {
  return (
    <BrowserRouter>
      <Routes>
        <Route path="/" element={<div><h1>Options Engine</h1></div>} />
      </Routes>
    </BrowserRouter>
  )
}

export default App
