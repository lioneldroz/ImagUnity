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

export async function postJson<T>(url: string): Promise<T> {
  const response = await check(await fetch(url, { method: 'POST' }))
  return (await response.json()) as T
}
