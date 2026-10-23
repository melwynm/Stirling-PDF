import { beforeEach, describe, expect, it, vi } from 'vitest';
import apiClient from '@app/services/apiClient';
import {
  completeRecipientSignature,
  declineRecipientSignature,
  downloadRecipientDocument,
  getSigningErrorCode,
  markRecipientViewed,
  newIdempotencyKey,
} from '@app/services/recipientSigningService';

vi.mock('@app/services/apiClient', () => ({
  default: {
    get: vi.fn(),
    post: vi.fn(),
  },
}));

describe('recipientSigningService', () => {
  beforeEach(() => {
    vi.mocked(apiClient.get).mockReset();
    vi.mocked(apiClient.post).mockReset();
  });

  it('sends access codes in a header when downloading the document', async () => {
    const blob = new Blob(['pdf'], { type: 'application/pdf' });
    vi.mocked(apiClient.get).mockResolvedValue({ data: blob });

    await expect(downloadRecipientDocument('token/value', 'secret-code')).resolves.toBe(blob);

    expect(apiClient.get).toHaveBeenCalledWith(
      '/api/v1/security/e-sign/recipients/token%2Fvalue/download',
      {
        responseType: 'blob',
        headers: { 'X-Signing-Access-Code': 'secret-code' },
      },
    );
  });

  it('submits consent and per-field values without putting secrets in the URL', async () => {
    vi.mocked(apiClient.post).mockResolvedValue({ data: { request: { id: 'request-1' } } });
    const input = {
      signerName: 'Ada Lovelace',
      signatureType: 'typed' as const,
      accessCode: 'secret-code',
      consentAccepted: true,
      consentText: 'I agree.',
      fieldValues: { 'field-1': 'Ada Lovelace' },
    };

    await completeRecipientSignature('public-token', input);

    expect(apiClient.post).toHaveBeenCalledWith(
      '/api/v1/security/e-sign/recipients/public-token/sign',
      input,
    );
  });

  it('sends the expected revision and idempotency key with a decline', async () => {
    vi.mocked(apiClient.post).mockResolvedValue({ data: { request: { id: 'request-1' } } });

    await declineRecipientSignature('public-token', 'Wrong document', undefined, {
      expectedWorkflowRevision: 5,
      idempotencyKey: 'decline-attempt-1',
    });

    expect(apiClient.post).toHaveBeenCalledWith(
      '/api/v1/security/e-sign/recipients/public-token/decline',
      { reason: 'Wrong document', accessCode: undefined, expectedWorkflowRevision: 5, idempotencyKey: 'decline-attempt-1' },
    );
  });

  it('returns the refreshed request after recording a view', async () => {
    vi.mocked(apiClient.post).mockResolvedValue({ data: { request: { id: 'request-1', workflowRevision: 9 } } });

    await expect(markRecipientViewed('public-token')).resolves.toEqual({ id: 'request-1', workflowRevision: 9 });
  });

  it('generates distinct idempotency keys', () => {
    const first = newIdempotencyKey();
    expect(first).toMatch(/^[A-Za-z0-9._:-]{1,128}$/);
    expect(newIdempotencyKey()).not.toBe(first);
  });

  it('reads the stable error code from a problem details response', () => {
    expect(getSigningErrorCode({ response: { status: 409, data: { code: 'WORKFLOW_REVISION_STALE' } } }))
      .toBe('WORKFLOW_REVISION_STALE');
    expect(getSigningErrorCode({ response: { status: 500, data: {} } })).toBeUndefined();
    expect(getSigningErrorCode(new Error('network'))).toBeUndefined();
  });
});
