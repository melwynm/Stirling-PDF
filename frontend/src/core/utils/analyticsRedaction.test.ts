import { describe, expect, it } from 'vitest';
import { redactSigningToken, redactSigningTokens } from '@app/utils/analyticsRedaction';

const TOKEN = 'Zx9-Q_aB3cD4eF5gH6iJ7kL8mN9oP0qR1sT2uV3wX4y';

describe('analyticsRedaction', () => {
  it('removes the token from recipient page URLs', () => {
    expect(redactSigningToken(`https://sign.example.com/sign-request/${TOKEN}?step=review#top`)).toBe(
      'https://sign.example.com/sign-request/[redacted]?step=review#top'
    );
    expect(redactSigningToken(`/sign-request/${TOKEN}`)).toBe('/sign-request/[redacted]');
  });

  it('removes the token from recipient API URLs in error messages', () => {
    expect(
      redactSigningToken(`Request failed: GET /api/v1/security/e-sign/recipients/${TOKEN}/download`)
    ).toBe('Request failed: GET /api/v1/security/e-sign/recipients/[redacted]/download');
  });

  it('leaves unrelated URLs unchanged', () => {
    const url = 'https://app.example.com/merge?file=contract.pdf';
    expect(redactSigningToken(url)).toBe(url);
  });

  it('redacts nested event properties without altering other values', () => {
    const timestamp = new Date('2026-10-08T12:00:00Z');
    const event = {
      event: '$pageview',
      timestamp,
      properties: {
        $current_url: `https://sign.example.com/sign-request/${TOKEN}`,
        $pathname: `/sign-request/${TOKEN}`,
        $exception_list: [{ value: `GET /api/v1/security/e-sign/recipients/${TOKEN}` }],
        count: 3,
        flag: true,
        missing: null,
      },
    };

    const redacted = redactSigningTokens(event);

    expect(JSON.stringify(redacted)).not.toContain(TOKEN);
    expect(redacted.properties.$pathname).toBe('/sign-request/[redacted]');
    expect(redacted.properties.count).toBe(3);
    expect(redacted.properties.flag).toBe(true);
    expect(redacted.properties.missing).toBeNull();
    expect(redacted.timestamp).toBe(timestamp);
    expect(event.properties.$pathname).toBe(`/sign-request/${TOKEN}`);
  });
});
