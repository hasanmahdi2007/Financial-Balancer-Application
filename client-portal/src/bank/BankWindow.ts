/**
 * The bank's own connect window, where the user signs in to their bank.
 *
 * An interface for the same reason `AuthGateway` is one: it is an outside service the client depends
 * on, and tests put an in-memory one in its place so none of them loads a script from the network.
 */
export interface BankWindow {
  /**
   * Opens the window for one link token. Resolves to the one-time public token when the user finished
   * connecting, or null when they closed the window without doing so - which is a choice, not an error.
   */
  open(linkToken: string): Promise<string | null>;
}

/** Plaid's documented loader. Fetched only when someone actually chooses to connect a bank. */
const PLAID_LINK_SCRIPT = 'https://cdn.plaid.com/link/v2/stable/link-initialize.js';

interface PlaidHandler {
  open(): void;
  destroy(): void;
}

interface PlaidLink {
  create(options: {
    token: string;
    onSuccess(publicToken: string): void;
    onExit(error: unknown): void;
  }): PlaidHandler;
}

declare global {
  interface Window {
    Plaid?: PlaidLink;
  }
}

let loading: Promise<PlaidLink> | null = null;

function loadPlaid(): Promise<PlaidLink> {
  if (window.Plaid) return Promise.resolve(window.Plaid);
  loading ??= new Promise<PlaidLink>((resolve, reject) => {
    const script = document.createElement('script');
    script.src = PLAID_LINK_SCRIPT;
    script.async = true;
    script.onload = () => (window.Plaid ? resolve(window.Plaid) : reject(new Error('Plaid Link did not load')));
    script.onerror = () => {
      // Forget the failure, so pressing the button again tries again rather than failing forever.
      loading = null;
      script.remove();
      reject(new Error('Plaid Link could not be loaded'));
    };
    document.head.appendChild(script);
  });
  return loading;
}

/** Plaid Link, loaded from Plaid on first use. */
export const plaidWindow: BankWindow = {
  async open(linkToken) {
    const plaid = await loadPlaid();
    return new Promise<string | null>((resolve) => {
      const handler = plaid.create({
        token: linkToken,
        onSuccess: (publicToken) => {
          handler.destroy();
          resolve(publicToken);
        },
        onExit: () => {
          handler.destroy();
          resolve(null);
        },
      });
      handler.open();
    });
  },
};
