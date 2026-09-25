import { useLocation } from 'react-router';
import { useApi } from '../api/ApiProvider';
import { bank } from '../api/bank';
import { useRemote } from '../api/useRemote';

/**
 * Whether to point this user at their bank at all: when connecting is offered where they live, or
 * when they already have one connected and need a way back to it.
 *
 * Asked of the server rather than worked out here from the profile's country, so the rule about
 * which countries have banks we can reach lives in exactly one place. Asked again on each page,
 * because changing where you live must change the menu without a reload.
 */
export function useBankOffer(): boolean {
  const api = useApi();
  const { pathname } = useLocation();
  const answer = useRemote(`bank-offer:${pathname}`, (signal) => bank.get(api, signal));
  return answer.state === 'ready' && (answer.data.offered || answer.data.connected);
}
