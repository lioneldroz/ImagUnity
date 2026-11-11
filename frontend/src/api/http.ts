/** Erreur HTTP de l'API, avec le détail renvoyé par le backend (ProblemDetail) s'il existe. */
export class ApiError extends Error {
  readonly status: number

  constructor(status: number, message: string) {
    super(message)
    this.status = status
  }
}

async function check(response: Response): Promise<Response> {
  if (response.ok) {
    return response
  }
  let detail = `HTTP ${response.status}`
  try {
    const problem = (await response.json()) as { detail?: string }
    if (problem.detail) {
      detail = problem.detail
    }
  } catch {
    // corps absent ou non JSON : on garde le code HTTP
  }
  throw new ApiError(response.status, detail)
}

export async function getJson<T>(url: string): Promise<T> {
  const response = await check(await fetch(url))
  return (await response.json()) as T
}

/**
 * En-tête exigé par la box sur les requêtes qui modifient l'état : un site tiers ne peut pas l'ajouter,
 * ce qui protège des requêtes intersites. L'authentification (HTTP Basic) est gérée par le navigateur.
 */
const CONSOLE_HEADER = { 'X-ImagUnity-Console': '1' }

export async function postJson<T>(url: string): Promise<T> {
  const response = await check(await fetch(url, { method: 'POST', headers: CONSOLE_HEADER }))
  return (await response.json()) as T
}
