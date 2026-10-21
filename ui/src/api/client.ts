// One small wrapper around fetch for every API call: adds the bearer token, sends and reads JSON, and turns the
// backend's error format (application/problem+json with a "code") into an ApiError the screens can show.

export class ApiError extends Error {
  constructor(
    public readonly status: number,
    public readonly code: string,
    message: string,
  ) {
    super(message);
    this.name = 'ApiError';
  }
}

interface RequestOptions {
  method?: 'GET' | 'POST';
  body?: unknown;
  token: string | undefined;
  /** Required by the API for money-moving calls, so a retried request cannot be applied twice. */
  idempotencyKey?: string;
}

export async function apiFetch<T>(baseUrl: string, path: string, options: RequestOptions): Promise<T> {
  const headers: Record<string, string> = { Accept: 'application/json' };
  if (options.token) headers.Authorization = `Bearer ${options.token}`;
  if (options.body !== undefined) headers['Content-Type'] = 'application/json';
  if (options.idempotencyKey) headers['Idempotency-Key'] = options.idempotencyKey;

  let response: Response;
  try {
    response = await fetch(`${baseUrl}${path}`, {
      method: options.method ?? 'GET',
      headers,
      body: options.body === undefined ? undefined : JSON.stringify(options.body),
    });
  } catch {
    // fetch rejects only when no answer arrived at all: server down, or the browser blocked the call (CORS).
    throw new ApiError(0, 'NETWORK', 'Could not reach the server. Is the platform running?');
  }

  if (!response.ok) {
    const problem = (await response.json().catch(() => ({}))) as { detail?: string; title?: string; code?: string };
    throw new ApiError(
      response.status,
      problem.code ?? `HTTP_${response.status}`,
      problem.detail ?? problem.title ?? `Request failed (${response.status})`,
    );
  }
  return (await response.json()) as T;
}
