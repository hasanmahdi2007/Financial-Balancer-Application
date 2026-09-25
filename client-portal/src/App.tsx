import { Navigate, Route, Routes } from 'react-router';
import { ApiProvider } from './api/ApiProvider';
import type { AuthGateway } from './auth/AuthGateway';
import { AuthProvider } from './auth/AuthProvider';
import { BankPage, BankWindowProvider } from './bank/BankPage';
import type { BankWindow } from './bank/BankWindow';
import { RequireAuth } from './auth/RequireAuth';
import { SignInPage } from './auth/SignInPage';
import { SignUpPage } from './auth/SignUpPage';
import { LocationStep } from './onboarding/LocationStep';
import { ManualFormStep } from './onboarding/ManualFormStep';
import { NextStepsPage } from './onboarding/NextStepsPage';
import { OnboardingDraftProvider } from './onboarding/OnboardingDraft';
import { AffordPage } from './decisions/AffordPage';
import { RebalancePage } from './decisions/RebalancePage';
import { GoalFormPage } from './goals/GoalForm';
import { Dashboard } from './plan/Dashboard';
import { HistoryPage, PastPlanPage } from './plan/HistoryPages';
import { MoneyPage } from './plan/MoneyPage';
import { MyPlansPage } from './plans/MyPlansPage';
import { NewPlanPage } from './plans/NewPlanPage';
import { QuestionsStep } from './setup/QuestionsStep';
import { ErrorBoundary } from './ui/ErrorBoundary';
import { Layout } from './ui/Layout';

/**
 * The whole client, with its outside dependencies passed in: the identity provider, the network and
 * the bank's connect window. `main.tsx` supplies the real ones; tests supply fakes and a router of their own.
 */
export function App({
  auth,
  fetchImpl,
  bankWindow,
}: {
  auth: AuthGateway;
  fetchImpl?: typeof fetch;
  bankWindow?: BankWindow;
}) {
  return (
    <ErrorBoundary>
      <AuthProvider gateway={auth}>
        <ApiProvider fetchImpl={fetchImpl}>
          <BankWindowProvider window={bankWindow}>
            <OnboardingDraftProvider>
              <Routes>
                <Route element={<Layout />}>
                  <Route path="/signin" element={<SignInPage />} />
                  <Route path="/signup" element={<SignUpPage />} />
                  <Route element={<RequireAuth />}>
                    <Route path="/setup/location" element={<LocationStep />} />
                    <Route path="/setup/:countryCode/my-city" element={<ManualFormStep />} />
                    <Route path="/setup/next" element={<NextStepsPage />} />
                    <Route path="/setup/questions" element={<QuestionsStep />} />
                    <Route path="/plan" element={<Dashboard />} />
                    <Route path="/plan/history" element={<HistoryPage />} />
                    <Route path="/plan/history/:planId" element={<PastPlanPage />} />
                    <Route path="/plans" element={<MyPlansPage />} />
                    <Route path="/plans/new" element={<NewPlanPage />} />
                    <Route path="/money" element={<MoneyPage />} />
                    <Route path="/bank" element={<BankPage />} />
                    <Route path="/goals/new" element={<GoalFormPage />} />
                    <Route path="/goals/:goalId" element={<GoalFormPage />} />
                    <Route path="/afford" element={<AffordPage />} />
                    <Route path="/rebalance" element={<RebalancePage />} />
                  </Route>
                  <Route path="*" element={<Navigate to="/plan" replace />} />
                </Route>
              </Routes>
            </OnboardingDraftProvider>
          </BankWindowProvider>
        </ApiProvider>
      </AuthProvider>
    </ErrorBoundary>
  );
}
