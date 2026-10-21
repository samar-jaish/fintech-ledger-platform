import { describe, expect, it } from 'vitest';
import { appRoles, decodeClaims } from './tokens';

// A token whose middle part is {"preferred_username":"alice","realm_access":{"roles":[...]}} (signature is irrelevant here).
function fakeToken(claims: object): string {
  const part = (o: object) => btoa(JSON.stringify(o)).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
  return `${part({ alg: 'none' })}.${part(claims)}.sig`;
}

describe('tokens', () => {
  it('reads the username from the token', () => {
    expect(decodeClaims(fakeToken({ preferred_username: 'alice' })).preferred_username).toBe('alice');
  });

  it('keeps only the application roles, not Keycloak defaults', () => {
    const token = fakeToken({ realm_access: { roles: ['USER', 'ADMIN', 'offline_access', 'default-roles-ledger'] } });
    expect(appRoles(token)).toEqual(['USER', 'ADMIN']);
  });

  it('copes with accented names', () => {
    const utf8 = (s: string) => btoa(String.fromCharCode(...new TextEncoder().encode(s)));
    const body = utf8(JSON.stringify({ name: 'José' })).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
    expect(decodeClaims(`x.${body}.y`).name).toBe('José');
  });

  it('returns nothing for a malformed token instead of throwing', () => {
    expect(decodeClaims('not-a-jwt')).toEqual({});
    expect(appRoles('a.%%%.c')).toEqual([]);
  });
});
