import { Navigate, Route, Routes } from 'react-router';
import { ApiProvider } from './api/ApiProvider';
import type { AuthGateway } from './auth/AuthGateway';
import { AuthProvider } from './auth/AuthProvider';
import { RequireAuth } from './auth/RequireAuth';
import { SignInPage } from './auth/SignInPage';
import { SignUpPage } from './auth/SignUpPage';
import { LocationStep } from './onboarding/LocationStep';
import { ManualFormStep } from './onboarding/ManualFormStep';
import { NextStepsPage } from './onboarding/NextStepsPage';
import { OnboardingDraftProvider } from './onboarding/OnboardingDraft';
import { Layout } from './ui/Layout';

/**
 * The whole client, with its two outside dependencies passed in: the identity provider and the
 * network. `main.tsx` supplies the real ones; tests supply fakes and a router of their own.
 */
export function App({ auth, fetchImpl }: { auth: AuthGateway; fetchImpl?: typeof fetch }) {
  return (
    <AuthProvider gateway={auth}>
      <ApiProvider fetchImpl={fetchImpl}>
        <OnboardingDraftProvider>
          <Routes>
            <Route element={<Layout />}>
              <Route path="/signin" element={<SignInPage />} />
              <Route path="/signup" element={<SignUpPage />} />
              <Route element={<RequireAuth />}>
                <Route path="/setup/location" element={<LocationStep />} />
                <Route path="/setup/:countryCode/my-city" element={<ManualFormStep />} />
                <Route path="/setup/next" element={<NextStepsPage />} />
              </Route>
              <Route path="*" element={<Navigate to="/setup/location" replace />} />
            </Route>
          </Routes>
        </OnboardingDraftProvider>
      </ApiProvider>
    </AuthProvider>
  );
}
