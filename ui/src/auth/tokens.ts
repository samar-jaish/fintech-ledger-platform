// The access token is a JWT: three base64url parts separated by dots, the middle one holding the claims.
// The UI only reads it to show who is signed in and which roles they have. The APIs, not the UI, decide what is allowed.

export interface AccessClaims {
  sub?: string;
  preferred_username?: string;
  name?: string;
  realm_access?: { roles?: string[] };
  exp?: number;
}

export function decodeClaims(token: string): AccessClaims {
  const payload = token.split('.')[1];
  if (!payload) return {};
  try {
    const base64 = payload.replace(/-/g, '+').replace(/_/g, '/');
    const bytes = Uint8Array.from(atob(base64), (c) => c.charCodeAt(0));
    return JSON.parse(new TextDecoder().decode(bytes)) as AccessClaims;
  } catch {
    return {};
  }
}

/** Application roles only (USER, ADMIN): Keycloak adds its own default-roles-* and offline_access entries too. */
export function appRoles(token: string): string[] {
  const roles = decodeClaims(token).realm_access?.roles ?? [];
  return roles.filter((r) => r === r.toUpperCase());
}
