import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import { BrowserRouter } from 'react-router';
import { App } from './App';
import { createSupabaseAuthGateway } from './auth/supabaseAuthGateway';
import { MissingConfigError, readConfig } from './config';
import './styles.css';

const root = createRoot(document.getElementById('root')!);

try {
  const auth = createSupabaseAuthGateway(readConfig(__BROWSER_ENV__));
  root.render(
    <StrictMode>
      <BrowserRouter>
        <App auth={auth} />
      </BrowserRouter>
    </StrictMode>,
  );
} catch (error) {
  if (!(error instanceof MissingConfigError)) throw error;
  // Only a developer ever sees this: a deployed build has its keys baked in.
  root.render(
    <main className="shell__main">
      <section className="card">
        <h1>The client is not configured</h1>
        <p>{error.message}</p>
      </section>
    </main>,
  );
}
