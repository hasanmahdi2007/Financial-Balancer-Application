/**
 * The one place requests leave the browser.
 *
 * Every call carries the user's access token, because every route sits behind the gateway. A
 * refused token ends the session and returns the user to sign-in: rendering a dashboard of empty
 * states instead would look exactly like a data bug, and nobody would think to sign in again.
 */

/** A failure worth showing as-is. `message` is written for the person reading the screen. */
export class ApiError extends Error {
  constructor(
    message: string,
    readonly status: number | null,
  ) {
    super(message);
    this.name = 'ApiError';
  }
}

/** The session is over. The caller has already been told; the screen should render nothing more. */
export class SessionEndedError extends Error {
  constructor() {
    super('Your session has ended.');
    this.name = 'SessionEndedError';
  }
}

export const SESSION_ENDED_NOTICE = 'Your session ended, so we signed you out. Sign in again to carry on.';

const UNREACHABLE = "We couldn't reach the server. Check your connection, then try again.";
const SERVER_FAULT = 'Something went wrong on our side. Try again in a moment.';
const REQUEST_REFUSED = "That didn't work. Try again, and if it keeps happening, reload the page.";

export interface ApiClientDeps {
  getAccessToken(): Promise<string | null>;
  /** Called once per refused request, with wording for the sign-in page. */
  onUnauthorized(notice: string): void;
  fetchImpl?: typeof fetch;
}

export interface ApiClient {
  getJson<T>(path: string, signal?: AbortSignal): Promise<T>;
  /**
   * A write. `body` is serialised as JSON; pass `undefined` for a route that takes none.
   *
   * Resolves to `undefined` when the server answers with no body, which is what a 204 from
   * `DELETE /api/v1/line-items/{id}` is. Callers that expect nothing back type `T` as `void`.
   */
  sendJson<T>(method: 'POST' | 'PUT' | 'DELETE', path: string, body?: unknown, signal?: AbortSignal): Promise<T>;
}

/**
 * RFC 9457 problem details, which is what Boot 4 answers errors with. `detail` is the sentence
 * meant for a person; `title` is the fallback.
 */
async function problemMessage(response: Response): Promise<string | null> {
  try {
    const body = (await response.json()) as { detail?: unknown; title?: unknown };
    if (typeof body.detail === 'string' && body.detail) return body.detail;
    if (typeof body.title === 'string' && body.title) return body.title;
  } catch {
    // Not JSON - an HTML error page from a proxy, say. The generic wording is the honest answer.
  }
  return null;
}

/**
 * A body the server sent but did not fill. `DELETE` answers 204, and a 200 with an empty body is
 * indistinguishable from it here; both mean "nothing to read", not "malformed JSON".
 */
async function readBody<T>(response: Response): Promise<T> {
  if (response.status === 204) return undefined as T;
  const text = await response.text();
  if (!text) return undefined as T;
  return JSON.parse(text) as T;
}

export function createApiClient(deps: ApiClientDeps): ApiClient {
  const fetchImpl = deps.fetchImpl ?? ((input, init) => fetch(input, init));

  /**
   * Every request goes through here, so the token, the refusal handling and the wording for a
   * failure are decided once. A second path would eventually forget one of the three.
   */
  async function request<T>(method: string, path: string, body: unknown, signal?: AbortSignal): Promise<T> {
    const token = await deps.getAccessToken();
    if (!token) {
      deps.onUnauthorized(SESSION_ENDED_NOTICE);
      throw new SessionEndedError();
    }

    const headers: Record<string, string> = {
      Accept: 'application/json',
      Authorization: `Bearer ${token}`,
    };
    if (body !== undefined) headers['Content-Type'] = 'application/json';

    let response: Response;
    try {
      response = await fetchImpl(path, {
        method,
        signal,
        headers,
        ...(body === undefined ? {} : { body: JSON.stringify(body) }),
      });
    } catch (error) {
      if (error instanceof DOMException && error.name === 'AbortError') throw error;
      throw new ApiError(UNREACHABLE, null);
    }

    if (response.status === 401) {
      deps.onUnauthorized(SESSION_ENDED_NOTICE);
      throw new SessionEndedError();
    }
    if (!response.ok) {
      const fallback = response.status >= 500 ? SERVER_FAULT : REQUEST_REFUSED;
      throw new ApiError((await problemMessage(response)) ?? fallback, response.status);
    }
    return readBody<T>(response);
  }

  return {
    getJson: <T,>(path: string, signal?: AbortSignal) => request<T>('GET', path, undefined, signal),
    sendJson: <T,>(method: 'POST' | 'PUT' | 'DELETE', path: string, body?: unknown, signal?: AbortSignal) =>
      request<T>(method, path, body, signal),
  };
}
