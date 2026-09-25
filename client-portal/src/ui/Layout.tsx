import { Link, NavLink, Outlet } from 'react-router';
import { useAuth } from '../auth/AuthProvider';
import { Icon, type IconName } from './Icon';

/** Everywhere a signed-in person can go, in the order they tend to need it. */
const MENU: { to: string; label: string; icon: IconName; end?: boolean }[] = [
  { to: '/plan', label: 'Your plan', icon: 'plan', end: true },
  { to: '/goals', label: 'Your goals', icon: 'check', end: true },
  { to: '/goals/new', label: 'Add a goal', icon: 'plus' },
  { to: '/afford', label: 'Can I afford this?', icon: 'bag' },
  { to: '/rebalance', label: 'Give me more for something', icon: 'sliders' },
  { to: '/plans/new', label: 'New plan', icon: 'spark' },
  { to: '/plans', label: 'My plans', icon: 'pin', end: true },
  { to: '/plan/history', label: 'Every plan so far', icon: 'clock' },
  { to: '/money', label: 'Your money', icon: 'wallet' },
  { to: '/bank', label: 'Connect your bank', icon: 'bank' },
  { to: '/setup/location', label: 'Where you live', icon: 'pin' },
];

export function Layout() {
  const { status, session, signOut } = useAuth();
  const signedIn = status === 'signed-in';
  return (
    <div className={signedIn ? 'shell shell--app' : 'shell shell--welcome'}>
      <header className="shell__header">
        <Link to="/plan" className="shell__brand">
          Financial Balancer
        </Link>
        {signedIn ? (
          <div className="shell__account">
            {session?.email ? <span className="shell__email">{session.email}</span> : null}
            <button type="button" className="button button--quiet" onClick={() => void signOut()}>
              Sign out
            </button>
          </div>
        ) : null}
      </header>
      <div className="shell__body">
        {signedIn ? (
          // Outside <main> on purpose: the menu is the frame, not the page, and a page that must have
          // nothing on it to press - a past plan - is checked by looking inside <main>.
          <nav className="sidebar" aria-label="Main">
            {MENU.map((item) => (
              <NavLink key={item.to} to={item.to} end={item.end} className="sidebar__link">
                <Icon name={item.icon} />
                <span>{item.label}</span>
              </NavLink>
            ))}
          </nav>
        ) : (
          <aside className="welcome" aria-label="About Financial Balancer">
            <p className="welcome__eyebrow">Your money, planned around your life</p>
            <h2 className="welcome__title">A plan built from what things really cost where you live.</h2>
            <ul className="welcome__points">
              <li>
                <span className="welcome__icon welcome__icon--blue">
                  <Icon name="pin" />
                </span>
                Starts from real prices in your city, and says where every figure came from.
              </li>
              <li>
                <span className="welcome__icon welcome__icon--orange">
                  <Icon name="spark" />
                </span>
                Add a goal and see exactly what it changes for everything else.
              </li>
              <li>
                <span className="welcome__icon welcome__icon--green">
                  <Icon name="shield" />
                </span>
                Never cuts below what you need to enjoy life.
              </li>
            </ul>
          </aside>
        )}
        <main className="shell__main">
          <Outlet />
        </main>
      </div>
    </div>
  );
}
