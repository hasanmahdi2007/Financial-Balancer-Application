import { Link, NavLink, Outlet } from 'react-router';
import { useAuth } from '../auth/AuthProvider';
import { useBankOffer } from '../bank/useBankOffer';
import { Icon, type IconName } from './Icon';

/** Everywhere a signed-in person can go, in the order they tend to need it. */
const MENU: { to: string; label: string; icon: IconName; end?: boolean; bankOnly?: boolean }[] = [
  { to: '/plan', label: 'Your plan', icon: 'plan', end: true },
  { to: '/goals', label: 'Your goals', icon: 'check', end: true },
  { to: '/goals/new', label: 'Add a goal', icon: 'plus' },
  { to: '/afford', label: 'Can I afford this?', icon: 'bag' },
  { to: '/rebalance', label: 'Give me more for something', icon: 'sliders' },
  { to: '/plans/new', label: 'New plan', icon: 'spark' },
  { to: '/plans', label: 'My plans', icon: 'pin', end: true },
  { to: '/plan/history', label: 'Every plan so far', icon: 'clock' },
  { to: '/money', label: 'Your money', icon: 'wallet' },
  // Only where a bank can actually be connected: offering it to someone in a country the bank
  // provider cannot reach would send them into a window with no bank of theirs in it.
  { to: '/bank', label: 'Connect your bank', icon: 'bank', bankOnly: true },
  { to: '/setup/location', label: 'Where you live', icon: 'pin' },
];

/** The menu, which only exists for a signed-in person - so only then is the bank asked about. */
function Menu() {
  const bankOffered = useBankOffer();
  return (
    <nav className="sidebar" aria-label="Main">
      {MENU.filter((item) => !item.bankOnly || bankOffered).map((item) => (
        <NavLink key={item.to} to={item.to} end={item.end} className="sidebar__link">
          <Icon name={item.icon} />
          <span>{item.label}</span>
        </NavLink>
      ))}
    </nav>
  );
}

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
          <Menu />
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
