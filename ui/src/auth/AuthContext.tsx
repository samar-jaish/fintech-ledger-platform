import { createContext, useCallback, useContext, useEffect, useMemo, useState, type ReactNode } from 'react';
import type { User } from 'oidc-client-ts';
import { completeLoginIfReturning, login, logout, userManager } from './auth';
import { appRoles, decodeClaims } from './tokens';

export type AuthState =
  | { status: 'loading' }
  | { status: 'anonymous' }
  | { status: 'error'; message: string }
  | { status: 'authenticated'; username: string; displayName: string; roles: string[]; accessToken: string };

interface AuthApi {
  state: AuthState;
  login: () => void;
  logout: () => void;
  /** The current access token (kept fresh by the library), for the Authorization header. */
  getToken: () => string | undefined;
}

const AuthContext = createContext<AuthApi | undefined>(undefined);

function toState(user: User | null): AuthState {
  if (!user || user.expired) return { status: 'anonymous' };
  const claims = decodeClaims(user.access_token);
  const username = claims.preferred_username ?? claims.sub ?? 'unknown';
  return {
    status: 'authenticated',
    username,
    displayName: claims.name ?? username,
    roles: appRoles(user.access_token),
    accessToken: user.access_token,
  };
}

export function AuthProvider({ children }: { children: ReactNode }) {
  const [state, setState] = useState<AuthState>({ status: 'loading' });

  useEffect(() => {
    let active = true;
    completeLoginIfReturning()
      .then((user) => active && setState(toState(user)))
      .catch((e: unknown) => active && setState({ status: 'error', message: e instanceof Error ? e.message : 'Login failed' }));

    const events = userManager().events;
    const refresh = (user: User) => setState(toState(user));
    const gone = () => setState({ status: 'anonymous' });
    events.addUserLoaded(refresh);
    events.addUserUnloaded(gone);
    events.addAccessTokenExpired(gone);
    return () => {
      active = false;
      events.removeUserLoaded(refresh);
      events.removeUserUnloaded(gone);
      events.removeAccessTokenExpired(gone);
    };
  }, []);

  const token = state.status === 'authenticated' ? state.accessToken : undefined;
  const getToken = useCallback(() => token, [token]);
  const api = useMemo<AuthApi>(
    () => ({ state, login: () => void login(), logout: () => void logout(), getToken }),
    [state, getToken],
  );
  return <AuthContext.Provider value={api}>{children}</AuthContext.Provider>;
}

export function useAuth(): AuthApi {
  const ctx = useContext(AuthContext);
  if (!ctx) throw new Error('useAuth must be used inside <AuthProvider>');
  return ctx;
}
