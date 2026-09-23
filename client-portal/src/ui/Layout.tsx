import { Link, Outlet } from 'react-router';
import { useAuth } from '../auth/AuthProvider';

export function Layout() {
  const { status, session, signOut } = useAuth();
  return (
    <div className="shell">
      <header className="shell__header">
        <Link to="/plan" className="shell__brand">
          Financial Balancer
        </Link>
        {status === 'signed-in' ? (
          <div className="shell__account">
            {session?.email ? <span className="shell__email">{session.email}</span> : null}
            <button type="button" className="button button--quiet" onClick={() => void signOut()}>
              Sign out
            </button>
          </div>
        ) : null}
      </header>
      <main className="shell__main">
        <Outlet />
      </main>
    </div>
  );
}
