import { UserManager, WebStorageStateStore, type User } from 'oidc-client-ts';
import { config } from '../config';

// Browser login with the authorization code flow and PKCE:
//  1. the app sends the browser to Keycloak's login page (the app never sees the password);
//  2. Keycloak sends it back with a one-time code in the address;
//  3. the app exchanges the code for tokens, proving it started the login with a secret (the PKCE verifier) that it
//     generated and kept. A stolen code is useless without that verifier, which is why no client secret is needed.
// Tokens are kept in sessionStorage: they survive a page refresh but disappear when the tab closes.

let manager: UserManager | undefined;

export function userManager(): UserManager {
  manager ??= new UserManager({
    authority: config.keycloakAuthority,
    client_id: config.clientId,
    redirect_uri: `${window.location.origin}/`,
    post_logout_redirect_uri: `${window.location.origin}/`,
    response_type: 'code',
    scope: 'openid profile',
    userStore: new WebStorageStateStore({ store: window.sessionStorage }),
    automaticSilentRenew: true,
  });
  return manager;
}

export function login(): Promise<void> {
  return userManager().signinRedirect();
}

export function logout(): Promise<void> {
  return userManager().signoutRedirect();
}

export function currentUser(): Promise<User | null> {
  return userManager().getUser();
}

let redirectResult: Promise<User | null> | undefined;

/**
 * If the address holds Keycloak's answer (?code=...&state=...), exchange it for tokens and clean the address.
 * Memoised because React's development mode runs effects twice and a login code can be used only once.
 */
export function completeLoginIfReturning(): Promise<User | null> {
  const params = new URLSearchParams(window.location.search);
  if (!params.has('code') || !params.has('state')) return currentUser();
  redirectResult ??= userManager()
    .signinRedirectCallback()
    .finally(() => window.history.replaceState({}, document.title, window.location.pathname));
  return redirectResult;
}
