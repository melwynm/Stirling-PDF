import { beforeEach, describe, expect, it, vi } from 'vitest';
import apiClient from '@app/services/apiClient';
import {
  completeRecipientSignature,
  downloadRecipientDocument,
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
});
