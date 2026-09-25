import { createContext, useContext, useEffect, useState, type ReactNode } from 'react';
import { Link, useNavigate } from 'react-router';
import { useApi } from '../api/ApiProvider';
import { bank, type Bank, type BankConnection } from '../api/bank';
import { plan } from '../api/plan';
import { useAction } from '../api/useAction';
import { useRemote } from '../api/useRemote';
import { Failure, Loading } from '../ui/Feedback';
import { formatMoney } from '../ui/amount';
import { plaidWindow, type BankWindow } from './BankWindow';

const BankWindowContext = createContext<BankWindow>(plaidWindow);

/** Supplies the bank's connect window. `main.tsx` leaves the real one in place; tests pass a fake. */
export function BankWindowProvider({ window, children }: { window?: BankWindow; children: ReactNode }) {
  return <BankWindowContext.Provider value={window ?? plaidWindow}>{children}</BankWindowContext.Provider>;
}

/** How often to look again while a bank is still importing. */
const IMPORT_CHECK_MS = 3000;

/**
 * Connecting a bank, and what it holds once connected.
 *
 * The page never asks for a bank login. The button opens the bank's own window, run by Plaid, and
 * what comes back is a one-time token the server exchanges for read-only access. After that the bank
 * keeps itself current - the server refreshes it every few hours - and the plan uses what it shows
 * the next time it is worked out, which the page offers to do straight away.
 */
export function BankPage() {
  const api = useApi();
  const [version, setVersion] = useState(0);
  const current = useRemote(`bank-${version}`, (signal) => bank.get(api, signal));
  const reload = () => setVersion((n) => n + 1);

  // A re-check keeps what is on screen until the new answer arrives, rather than blanking the page
  // - and a half-finished "Yes, disconnect" with it - every few seconds.
  const [shown, setShown] = useState<Bank | null>(null);
  useEffect(() => {
    if (current.state === 'ready') setShown(current.data);
  }, [current]);

  // While anything is still importing, look again every few seconds until it has finished.
  const importing = shown !== null && shown.connections.some((c) => c.lastUpdated === null);
  useEffect(() => {
    if (!importing) return;
    const timer = setTimeout(reload, IMPORT_CHECK_MS);
    return () => clearTimeout(timer);
  }, [importing, version]);

  const data = current.state === 'ready' ? current.data : shown;
  return (
    <section className="card">
      <h1>Your bank</h1>
      {data === null && current.state === 'loading' ? <Loading what="your bank" /> : null}
      {current.state === 'failed' ? <Failure message={current.message} retry={current.retry} /> : null}
      {data !== null ? <BankDetails bank={data} onChange={reload} /> : null}
    </section>
  );
}

function BankDetails({ bank: held, onChange }: { bank: Bank; onChange(): void }) {
  const api = useApi();
  const navigate = useNavigate();
  const importing = held.connections.some((c) => c.lastUpdated === null);

  const replan = useAction(async () => {
    await plan.recompute(api);
    navigate('/plan');
  });
  const refresh = useAction(async () => {
    await bank.refresh(api);
    onChange();
  });

  if (!held.offered && !held.connected) {
    return (
      <>
        <div className="banner banner--info" role="note">
          <p>{held.notOffered}</p>
        </div>
        <div className="actions">
          <Link to="/money" className="button">
            Enter your figures
          </Link>
        </div>
      </>
    );
  }

  return (
    <>
      <p className="lede">
        {held.connected
          ? 'Your bank keeps your plan up to date: what you really spend, what you already save and what you hold.'
          : 'Connect your bank and your plan fills itself in from what you really spend, instead of what you remember.'}
      </p>
      <div className="banner banner--info" role="note">
        <p>{held.explanation}</p>
      </div>

      {held.connected ? (
        <>
          <h2>Connected</h2>
          <ul className="goals">
            {held.connections.map((connection) => (
              <ConnectionRow key={connection.id} connection={connection} onRemoved={onChange} />
            ))}
          </ul>

          {held.accounts.length > 0 ? (
            <>
              <h2>Your accounts</h2>
              <dl className="tiles">
                {held.accounts.map((account, index) => (
                  <div key={`${index}-${account.name}`} className={account.kind === 'Bank account' ? 'tile tile--green' : 'tile tile--orange'}>
                    <dt>
                      {account.name} · {account.kind}
                    </dt>
                    <dd>{formatMoney(account.balance)}</dd>
                    <p className="aside">
                      {account.meaning}
                      {account.available !== null ? ` · ${formatMoney(account.available)} available` : null}
                    </p>
                  </div>
                ))}
              </dl>
            </>
          ) : importing ? null : (
            <p className="aside">Your bank has not reported any accounts yet.</p>
          )}

          {replan.error ? <Failure message={replan.error} /> : null}
          {refresh.error ? <Failure message={refresh.error} /> : null}
          <div className="actions">
            <button type="button" className="button button--secondary" disabled={refresh.busy} onClick={() => void refresh.run()}>
              {refresh.busy ? 'Asking your bank…' : 'Check for new transactions'}
            </button>
            {held.offered ? <ConnectButton label="Connect another bank" secondary onConnected={onChange} /> : null}
            <button type="button" className="button" disabled={importing || replan.busy} onClick={() => void replan.run()}>
              {replan.busy ? 'Working out your plan…' : 'Update my plan with this'}
            </button>
          </div>
          {importing ? (
            <p className="aside" role="status">
              Your transactions are still coming in. Your plan can use them as soon as they have.
            </p>
          ) : null}
        </>
      ) : (
        <div className="actions">
          <ConnectButton label="Connect your bank" onConnected={onChange} />
        </div>
      )}
    </>
  );
}

function ConnectButton({ label, secondary, onConnected }: { label: string; secondary?: boolean; onConnected(): void }) {
  const api = useApi();
  const bankWindow = useContext(BankWindowContext);
  const connect = useAction(async () => {
    const { linkToken } = await bank.linkToken(api);
    const publicToken = await bankWindow.open(linkToken);
    // Closing the bank's window is a choice, not an error: nothing to say, nothing to change.
    if (publicToken === null) return;
    await bank.connect(api, publicToken);
    onConnected();
  });
  return (
    <>
      <button
        type="button"
        className={secondary ? 'button button--secondary' : 'button'}
        disabled={connect.busy}
        onClick={() => void connect.run()}
      >
        {connect.busy ? 'Connecting…' : label}
      </button>
      {connect.error ? <Failure message={connect.error} /> : null}
    </>
  );
}

function ConnectionRow({ connection, onRemoved }: { connection: BankConnection; onRemoved(): void }) {
  const api = useApi();
  const [confirming, setConfirming] = useState(false);
  const remove = useAction(async () => {
    await bank.disconnect(api, connection.id);
    onRemoved();
  });

  return (
    <li className="goal">
      <p>
        <strong>Bank connected {formatDay(connection.connectedAt)}</strong>
      </p>
      <p className="goal__meta" role="status">
        {connection.status}
        {connection.lastUpdated !== null ? ` · last checked ${formatMoment(connection.lastUpdated)}` : null}
      </p>
      {remove.error ? <Failure message={remove.error} /> : null}
      {confirming ? (
        <div className="actions">
          <p>Disconnecting deletes everything we imported from this bank, and your plan goes back to what you told us.</p>
          <button type="button" className="button button--secondary" onClick={() => setConfirming(false)}>
            Keep it
          </button>
          <button type="button" className="button button--orange" disabled={remove.busy} onClick={() => void remove.run()}>
            {remove.busy ? 'Disconnecting…' : 'Yes, disconnect'}
          </button>
        </div>
      ) : (
        <button type="button" className="button button--quiet" onClick={() => setConfirming(true)}>
          Disconnect
        </button>
      )}
    </li>
  );
}

function formatDay(iso: string): string {
  return new Date(iso).toLocaleDateString(undefined, { day: 'numeric', month: 'long', year: 'numeric' });
}

function formatMoment(iso: string): string {
  return new Date(iso).toLocaleString(undefined, {
    day: 'numeric',
    month: 'short',
    hour: 'numeric',
    minute: '2-digit',
  });
}
