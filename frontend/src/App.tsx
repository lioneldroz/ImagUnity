import { useQuery } from '@tanstack/react-query'
import { fetchBoxInfo } from './api/box'
import { useConsoleEvents, type StreamStatus } from './api/events'
import { InboundTable } from './components/InboundTable'
import { OutboundTable } from './components/OutboundTable'
import { OverviewCards } from './components/OverviewCards'
import { DirectoryCard } from './components/DirectoryCard'
import { PeerList } from './components/PeerList'

const STREAM_LABELS: Record<StreamStatus, string> = {
  connecting: 'connexion…',
  live: 'en direct',
  offline: 'hors ligne, reconnexion…',
}

function App() {
  const box = useQuery({ queryKey: ['box'], queryFn: fetchBoxInfo, refetchInterval: 60_000 })
  const stream = useConsoleEvents()

  return (
    <main className="console">
      <header className="console-header">
        <div>
          <h1>ImagUnity</h1>
          <p className="subtitle">
            {box.isSuccess ? `${box.data.name} · version ${box.data.version}` : 'Console de la box'}
          </p>
        </div>
        <span className={`stream stream-${stream}`}>{STREAM_LABELS[stream]}</span>
      </header>

      {box.isError && <p className="error">Backend injoignable ({box.error.message})</p>}

      <section className="card">
        <h2>Indicateurs</h2>
        <OverviewCards />
      </section>

      <section className="card">
        <h2>Envois</h2>
        <p className="muted small">Cliquer sur une ligne pour voir ses paquets.</p>
        <OutboundTable />
      </section>

      <section className="card">
        <h2>Réceptions</h2>
        <InboundTable />
      </section>

      <section className="card">
        <h2>Partenaires</h2>
        <PeerList />
      </section>

      <section className="card">
        <h2>Annuaire de l’organisation</h2>
        <DirectoryCard />
      </section>
    </main>
  )
}

export default App
