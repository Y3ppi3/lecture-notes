import { useEffect, useState } from 'react';
import { getSession, isSessionLoaded, loadSession, subscribeSession, type Session } from './session';

/** Текущая сессия и признак того, что она уже прочитана из SecureStore. */
export function useSession(): { session: Session | null; loaded: boolean } {
  const [state, setState] = useState(() => ({ session: getSession(), loaded: isSessionLoaded() }));
  useEffect(() => {
    const unsubscribe = subscribeSession((s) => setState({ session: s, loaded: true }));
    if (!isSessionLoaded()) loadSession().catch(() => setState({ session: null, loaded: true }));
    return unsubscribe;
  }, []);
  return state;
}
