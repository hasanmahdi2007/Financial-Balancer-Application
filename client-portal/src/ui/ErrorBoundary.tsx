import { Component, type ErrorInfo, type ReactNode } from 'react';

/**
 * The last line against a blank page.
 *
 * Everything else here handles the failures it can name - a refused token, a server that will not
 * answer. This catches the one nobody predicted: React unmounts the whole tree when a render
 * throws, and what the user is left looking at is a white screen with no way to tell whether the
 * app broke or their money is gone.
 */
interface State {
  failed: boolean;
}

export class ErrorBoundary extends Component<{ children: ReactNode }, State> {
  override state: State = { failed: false };

  static getDerivedStateFromError(): State {
    return { failed: true };
  }

  override componentDidCatch(error: Error, info: ErrorInfo): void {
    // Kept in the console rather than shown: the stack is for whoever is fixing it, and a stack
    // trace on screen tells the person at the keyboard nothing they can act on.
    console.error('The interface failed to render.', error, info.componentStack);
  }

  override render(): ReactNode {
    if (!this.state.failed) return this.props.children;
    return (
      <main className="shell__main">
        <section className="card card--narrow">
          <h1>Something broke on this page</h1>
          <p>
            This is a fault in the app, not something you did, and nothing you have entered has been sent anywhere.
            Reloading usually clears it.
          </p>
          <button type="button" className="button" onClick={() => window.location.reload()}>
            Reload the page
          </button>
        </section>
      </main>
    );
  }
}
