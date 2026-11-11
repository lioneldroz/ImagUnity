import { useQuery } from '@tanstack/react-query'
import { fetchBoxInfo } from './api/box'

function App() {
  const box = useQuery({ queryKey: ['box'], queryFn: fetchBoxInfo, refetchInterval: 10_000 })

  return (
    <main className="console">
      <header>
        <h1>ImagUnity</h1>
        <p className="subtitle">Console de la box</p>
      </header>

      <section className="card">
        <h2>Box</h2>
        {box.isPending && <p>Connexion au backend…</p>}
        {box.isError && (
          <p className="error">Backend injoignable ({box.error.message})</p>
        )}
        {box.isSuccess && (
          <dl>
            <dt>Nom</dt>
            <dd>{box.data.name}</dd>
            <dt>Version</dt>
            <dd>{box.data.version}</dd>
          </dl>
        )}
      </section>

      <section className="card">
        <h2>Transferts</h2>
        <p className="muted">Le suivi des transferts arrivera en phase 7.</p>
      </section>
    </main>
  )
}

export default App
