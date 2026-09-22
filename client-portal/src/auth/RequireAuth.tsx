import { Navigate, Outlet, useLocation } from 'react-router';
import { Loading } from '../ui/Feedback';
import { useAuth } from './AuthProvider';

/**
 * Sends anyone without a session to sign-in, remembering where they were headed. Nothing behind
 * this renders without a session, so a signed-out visitor never sees a screen of empty data.
 */
export function RequireAuth() {
  const { status } = useAuth();
  const location = useLocation();
  if (status === 'checking') return <Loading what="your account" />;
  if (status === 'signed-out') {
    return <Navigate to="/signin" replace state={{ from: location.pathname + location.search }} />;
  }
  return <Outlet />;
}
