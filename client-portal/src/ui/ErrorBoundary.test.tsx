import { render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { ErrorBoundary } from './ErrorBoundary';

function Boom(): never {
  throw new Error('a component gave up');
}

describe('an unexpected failure', () => {
  it('says so, rather than leaving a white page nobody can read', () => {
    // React unmounts the whole tree when a render throws. Without this, the user is left looking at
    // nothing, with no way to tell whether the app broke or their money went missing.
    vi.spyOn(console, 'error').mockImplementation(() => {});

    render(
      <ErrorBoundary>
        <Boom />
      </ErrorBoundary>,
    );

    expect(screen.getByRole('heading', { name: 'Something broke on this page' })).toBeInTheDocument();
    expect(screen.getByText(/nothing you have entered has been sent anywhere/)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Reload the page' })).toBeInTheDocument();
  });

  it('stays out of the way when nothing is wrong', () => {
    render(
      <ErrorBoundary>
        <p>the app</p>
      </ErrorBoundary>,
    );

    expect(screen.getByText('the app')).toBeInTheDocument();
  });
});
