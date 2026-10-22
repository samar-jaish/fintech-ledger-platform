import { expect, test, type Page } from '@playwright/test';

// One journey through the real system: sign in at Keycloak, open two accounts, add money, send money, buy a share.
// It proves the pieces work together in a browser: the served UI, the login redirect, CORS, both APIs and the ledger.

async function signIn(page: Page, username: string, password: string) {
  await page.goto('/');
  await page.getByRole('button', { name: 'Sign in' }).click();
  // This is Keycloak's own login page, not part of our UI.
  await page.locator('#username').fill(username);
  await page.locator('#password').fill(password);
  await page.locator('#kc-login').click();
  await expect(page.getByRole('heading', { name: 'Your accounts' })).toBeVisible();
}

async function openAccount(page: Page, holder: string): Promise<string> {
  await page.getByLabel('Account holder').fill(holder);
  await page.getByRole('button', { name: 'Open account' }).click();
  await page.waitForURL(/\/accounts\/[0-9a-f-]{36}$/);
  return page.url().split('/').pop()!;
}

test('sign in, open accounts, add money, send money and buy a share', async ({ page }) => {
  await signIn(page, 'alice', 'alice123');

  // Alice keeps her accounts between runs, so each run uses names of its own and never picks up an old account.
  const tag = Date.now().toString(36);
  const mainName = `E2E Alice main ${tag}`;

  const first = await openAccount(page, mainName);
  await page.getByLabel('Amount to add').fill('1000');
  await page.getByRole('button', { name: 'Add money' }).click();
  await expect(page.getByLabel('Balance')).toContainText('1,000.00');

  await page.getByRole('link', { name: 'Accounts', exact: true }).click();
  const second = await openAccount(page, `E2E Alice savings ${tag}`);
  expect(second).not.toBe(first);

  await page.getByRole('link', { name: 'Accounts', exact: true }).click();
  await page.getByRole('link', { name: mainName }).click();
  await page.waitForURL(new RegExp(`/accounts/${first}$`));

  await page.getByLabel('To account id').fill(second);
  await page.getByLabel('Amount', { exact: true }).fill('25');
  await page.getByRole('button', { name: 'Send money' }).click();
  await expect(page.getByRole('status').filter({ hasText: 'Transfer sent.' })).toBeVisible();
  await expect(page.getByLabel('Balance')).toContainText('975.00');

  await page.getByRole('link', { name: 'Trading' }).click();
  await page.getByLabel('Quantity').fill('1');
  await page.getByRole('button', { name: 'Buy at market price' }).click();
  await expect(page.getByRole('status').filter({ hasText: /Bought 1 AAPL|Accepted/ })).toBeVisible();
  await expect(page.getByRole('table', { name: /Trade history/ })).toContainText('AAPL');
});

test('a person who is not signed in sees only the sign-in page', async ({ page }) => {
  await page.goto('/accounts');
  await expect(page.getByRole('button', { name: 'Sign in' })).toBeVisible();
  await expect(page.getByRole('heading', { name: 'Your accounts' })).toHaveCount(0);
});

test('a transfer appears live for the person who receives it, without reloading', async ({ browser, baseURL }) => {
  // Two people, two separate browser sessions.
  const bobContext = await browser.newContext({ baseURL });
  const aliceContext = await browser.newContext({ baseURL });
  const bob = await bobContext.newPage();
  const alice = await aliceContext.newPage();
  try {
    await signIn(bob, 'bob', 'bob123');
    const bobAccount = await openAccount(bob, 'E2E Bob');
    await expect(bob.getByLabel('Balance')).toContainText('0.00');
    // Wait until Bob's live connection is open before Alice sends, so the test shows a push, not a page load.
    await bob.getByRole('button', { name: 'Notifications' }).click();
    await expect(bob.getByText(/Live:/)).toBeVisible();
    await bob.keyboard.press('Escape');

    await signIn(alice, 'alice', 'alice123');
    await openAccount(alice, 'E2E Alice sender');
    await alice.getByLabel('Amount to add').fill('100');
    await alice.getByRole('button', { name: 'Add money' }).click();
    await expect(alice.getByLabel('Balance')).toContainText('100.00');
    await alice.getByLabel('To account id').fill(bobAccount);
    await alice.getByLabel('Amount', { exact: true }).fill('40');
    await alice.getByRole('button', { name: 'Send money' }).click();
    await expect(alice.getByRole('status').filter({ hasText: 'Transfer sent.' })).toBeVisible();

    // Bob never touched his page: the badge and the balance change by themselves.
    await expect(bob.getByRole('button', { name: /Notifications, \d+ new/ })).toBeVisible();
    await expect(bob.getByLabel('Balance')).toContainText('40.00');
    await bob.getByRole('button', { name: /Notifications/ }).click();
    await expect(bob.getByText(/You received 40\.00 from account/)).toBeVisible();
  } finally {
    await bobContext.close();
    await aliceContext.close();
  }
});
