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

export function createApiClient(deps: ApiClientDeps): ApiClient {
  const fetchImpl = deps.fetchImpl ?? ((input, init) => fetch(input, init));

  return {
    async getJson<T>(path: string, signal?: AbortSignal): Promise<T> {
      const token = await deps.getAccessToken();
      if (!token) {
        deps.onUnauthorized(SESSION_ENDED_NOTICE);
        throw new SessionEndedError();
      }

      let response: Response;
      try {
        response = await fetchImpl(path, {
          signal,
          headers: { Accept: 'application/json', Authorization: `Bearer ${token}` },
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
      return (await response.json()) as T;
    },
  };
}
