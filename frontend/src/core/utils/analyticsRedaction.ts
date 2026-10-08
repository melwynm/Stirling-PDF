/**
 * Signing links carry a bearer token in their path: the recipient page is /sign-request/<token>
 * and its API calls go to /api/v1/security/e-sign/recipients/<token>/... Anyone holding the token
 * can act as that recipient, so analytics must never receive it. Page views, autocapture and
 * captured exceptions all include URLs, so every string in an event is redacted before it leaves
 * the browser.
 */
const SIGNING_TOKEN_PATTERN = /(\/(?:sign-request|e-sign\/recipients)\/)[^/?#\s"'<>]+/g;

export const REDACTED_SIGNING_TOKEN = '[redacted]';

export function redactSigningToken(value: string): string {
  return value.replace(SIGNING_TOKEN_PATTERN, `$1${REDACTED_SIGNING_TOKEN}`);
}

function isPlainObject(value: unknown): value is Record<string, unknown> {
  return Object.prototype.toString.call(value) === '[object Object]';
}

/** Returns a copy with signing tokens removed from every string, at any depth. */
export function redactSigningTokens<T>(value: T): T {
  if (typeof value === 'string') {
    return redactSigningToken(value) as T;
  }
  if (Array.isArray(value)) {
    return value.map((item) => redactSigningTokens(item)) as T;
  }
  if (isPlainObject(value)) {
    return Object.fromEntries(
      Object.entries(value).map(([key, item]) => [key, redactSigningTokens(item)])
    ) as T;
  }
  return value;
}
