// Where things live. The defaults match docker compose and the kind cluster (both publish the same local ports),
// so the app works with no configuration. Vite replaces import.meta.env.VITE_* when the app is built.
const env = import.meta.env;

export const config = {
  keycloakAuthority: env.VITE_KEYCLOAK_AUTHORITY ?? 'http://localhost:8180/realms/ledger',
  clientId: env.VITE_KEYCLOAK_CLIENT_ID ?? 'ledger-ui',
  ledgerApi: env.VITE_LEDGER_API ?? 'http://localhost:8080',
  tradingApi: env.VITE_TRADING_API ?? 'http://localhost:8082',
  notificationApi: env.VITE_NOTIFICATION_API ?? 'http://localhost:8084',
};
