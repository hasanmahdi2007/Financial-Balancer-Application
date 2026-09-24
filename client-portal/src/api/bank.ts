import type { ApiClient } from './http';

/**
 * Everything under `/api/v1/bank`, typed as the gateway serialises it. The route shapes are in
 * `api-gateway/happy-path.http`, steps 23 to 27.
 *
 * Nothing here ever carries the bank credential. The browser only ever holds a short-lived link token
 * to open the bank's own window, and the one-time public token that window hands back; the lasting
 * credential is exchanged, encrypted and kept on the server.
 */

export interface BankConnection {
  id: number;
  connectedAt: string;
  /** Null until the first import finishes. */
  lastUpdated: string | null;
  /** Written for a person: "Up to date", or that it is still importing. */
  status: string;
}

export interface BankAccount {
  name: string;
  /** "Bank account", "Credit card" or "Loan". */
  kind: string;
  balance: string;
  /** What the balance is: held, or owed. Never show `balance` without it. */
  meaning: string;
  available: string | null;
}

export interface Bank {
  connected: boolean;
  connections: BankConnection[];
  accounts: BankAccount[];
  /** What connecting does and does not allow, for beside the connect button. */
  explanation: string;
}

export const bank = {
  get: (api: ApiClient, signal?: AbortSignal) => api.getJson<Bank>('/api/v1/bank', signal),
  linkToken: (api: ApiClient) => api.sendJson<{ linkToken: string }>('POST', '/api/v1/bank/link-token'),
  connect: (api: ApiClient, publicToken: string) =>
    api.sendJson<Bank>('POST', '/api/v1/bank/connections', { publicToken }),
  refresh: (api: ApiClient) => api.sendJson<{ banks: number }>('POST', '/api/v1/bank/refresh'),
  disconnect: (api: ApiClient, id: number) => api.sendJson<void>('DELETE', `/api/v1/bank/connections/${id}`),
};
