interface Props {
  label: string
  tone: 'ok' | 'progress' | 'error' | 'warning'
}

export function StateBadge({ label, tone }: Props) {
  return <span className={`badge badge-${tone}`}>{label}</span>
}
