import { createContext, useContext, useMemo, type ReactNode } from 'react';
import { useAuth } from '../auth/AuthProvider';
import { createApiClient, type ApiClient } from './http';

const ApiContext = createContext<ApiClient | null>(null);

/** Builds the API client from the signed-in session, so no screen handles a token itself. */
export function ApiProvider({ fetchImpl, children }: { fetchImpl?: typeof fetch; children: ReactNode }) {
  const { gateway, endSession } = useAuth();
  const client = useMemo(
    () =>
      createApiClient({
        getAccessToken: async () => (await gateway.currentSession())?.accessToken ?? null,
        onUnauthorized: endSession,
        fetchImpl,
      }),
    [gateway, endSession, fetchImpl],
  );
  return <ApiContext.Provider value={client}>{children}</ApiContext.Provider>;
}

export function useApi(): ApiClient {
  const client = useContext(ApiContext);
  if (!client) throw new Error('useApi must be used inside <ApiProvider>.');
  return client;
}
