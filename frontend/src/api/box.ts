/** Réponse de GET /api/v1/box. */
export interface BoxInfo {
  name: string
  version: string
}

export async function fetchBoxInfo(): Promise<BoxInfo> {
  const response = await fetch('/api/v1/box')
  if (!response.ok) {
    throw new Error(`HTTP ${response.status}`)
  }
  return response.json() as Promise<BoxInfo>
}
