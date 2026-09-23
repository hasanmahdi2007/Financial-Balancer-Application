import '@testing-library/jest-dom/vitest';
import { cleanup } from '@testing-library/react';
import { afterEach, beforeEach, vi } from 'vitest';

afterEach(() => {
  cleanup();
  sessionStorage.clear();
});

// No test may touch the network (packet rule 9). A test that forgets to supply a fake fails loudly
// here instead of quietly reaching for whatever happens to be listening.
beforeEach(() => {
  vi.stubGlobal('fetch', () => {
    throw new Error('A test tried to use the network. Pass a fake fetch instead.');
  });
});
