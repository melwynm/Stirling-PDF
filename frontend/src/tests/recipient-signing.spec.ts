import { expect, test } from '@playwright/test';
import path from 'node:path';

for (const viewport of [{ width: 1440, height: 1000 }, { width: 390, height: 844 }]) {
  test(`public recipient signs and sees completion at ${viewport.width}px`, async ({ page }) => {
    await page.setViewportSize(viewport);
    const recipient = { id: 'recipient-1', name: 'Ada', email: 'ada@example.test', role: 'signer',
      deliveryChannel: 'email', signingOrder: 1, authenticationMethod: 'emailLink', status: 'SENT' };
    const request = { modelVersion: 1, id: 'request-1', title: 'Signing acceptance example',
      status: 'SENT', originalFilename: 'example.pdf', documentRevision: 0,
      createdAt: '', updatedAt: '', signingOrder: false, remindersEnabled: false,
      reminderIntervalHours: 48, recipients: [recipient], fields: [{
        id: 'signature-1', recipientId: recipient.id, name: 'signature', label: 'Your signature',
        type: 'signature', pageIndex: 0, bounds: { x: 0.1, y: 0.1, width: 0.3, height: 0.1 },
        required: true, readOnly: false, options: [],
      }] };
    let signCount = 0;
    await page.route('**/api/v1/security/e-sign/recipients/test-token**', async route => {
      const url = new URL(route.request().url());
      if (url.pathname.endsWith('/download')) {
        return route.fulfill({ contentType: 'application/pdf', path: path.resolve('../testing/test_pdf_1.pdf') });
      }
      if (url.pathname.endsWith('/sign')) {
        signCount += 1;
        expect(route.request().postDataJSON().consentAccepted).toBe(true);
        return route.fulfill({ json: { request: { ...request, status: 'COMPLETED' } } });
      }
      if (url.pathname.endsWith('/viewed')) return route.fulfill({ json: {} });
      return route.fulfill({ json: { request, recipient, downloadUrl: '/download' } });
    });
    await page.goto('/sign-request/test-token');
    await expect(page.getByRole('heading', { name: request.title })).toBeVisible();
    await expect(page.getByRole('link', { name: 'Download', exact: true })).toBeVisible();
    const signButton = page.getByRole('button', { name: 'Sign', exact: true });
    await expect(signButton).toBeDisabled();
    await page.getByRole('checkbox', { name: /I agree to sign/ }).check();
    await signButton.click();
    await expect(page.getByText('Signature recorded', { exact: true })).toBeVisible();
    await expect(page.getByText('All required recipients have completed this request.')).toBeVisible();
    expect(signCount).toBe(1);
    const overflow = await page.evaluate(() => document.documentElement.scrollWidth > window.innerWidth);
    expect(overflow).toBe(false);
    await page.screenshot({ path: test.info().outputPath(`recipient-${viewport.width}.png`), fullPage: true });
  });
}
