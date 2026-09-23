import { describe, expect, it, vi } from 'vitest';
import { ApiError, createApiClient, SessionEndedError } from './http';

const problem = (status: number, body: unknown) =>
  new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/problem+json' } });

function client(fetchImpl: typeof fetch, token: string | null = 'abc') {
  const onUnauthorized = vi.fn();
  return { api: createApiClient({ getAccessToken: async () => token, onUnauthorized, fetchImpl }), onUnauthorized };
}

describe('requests to the gateway', () => {
  it('are not sent at all without a session, and end the session instead', async () => {
    const fetchImpl = vi.fn<typeof fetch>();
    const { api, onUnauthorized } = client(fetchImpl, null);

    await expect(api.getJson('/api/x')).rejects.toBeInstanceOf(SessionEndedError);
    expect(fetchImpl).not.toHaveBeenCalled();
    expect(onUnauthorized).toHaveBeenCalledOnce();
  });

  it('end the session when the server refuses the token', async () => {
    const { api, onUnauthorized } = client(async () => new Response(null, { status: 401 }));

    await expect(api.getJson('/api/x')).rejects.toBeInstanceOf(SessionEndedError);
    expect(onUnauthorized).toHaveBeenCalledOnce();
  });

  it("surface the server's own sentence from a problem-details body", async () => {
    const { api } = client(async () =>
      problem(404, { title: 'Not Found', detail: 'We have no figures for that country.' }),
    );

    await expect(api.getJson('/api/x')).rejects.toThrow(new ApiError('We have no figures for that country.', 404));
  });

  it('fall back to plain wording when a failure has no readable body', async () => {
    const { api } = client(async () => new Response('<html>Bad gateway</html>', { status: 502 }));

    await expect(api.getJson('/api/x')).rejects.toThrow('Something went wrong on our side.');
  });

  it('say the server could not be reached when the network drops, rather than blaming the user', async () => {
    const { api } = client(async () => {
      throw new TypeError('Failed to fetch');
    });

    await expect(api.getJson('/api/x')).rejects.toThrow("We couldn't reach the server.");
  });
});
