/** Loading and failure states, so no screen renders an empty page while it waits or after it fails. */

export function Loading({ what }: { what: string }) {
  return (
    <p className="loading" role="status" aria-live="polite">
      Loading {what}…
    </p>
  );
}

export function Failure({ message, retry }: { message: string; retry?: () => void }) {
  return (
    <div className="banner banner--error" role="alert">
      <p>{message}</p>
      {retry ? (
        <button type="button" className="button button--secondary" onClick={retry}>
          Try again
        </button>
      ) : null}
    </div>
  );
}
