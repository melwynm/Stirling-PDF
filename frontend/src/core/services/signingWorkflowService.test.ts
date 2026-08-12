import { describe, expect, it } from 'vitest';
import {
  buildSignatureRequestFormData,
  type CreateSignatureRequestInput,
} from '@app/services/signingWorkflowService';

describe('signingWorkflowService', () => {
  it('builds the versioned sender configuration as multipart JSON', () => {
    const file = new File(['%PDF-1.7'], 'contract.pdf', { type: 'application/pdf' });
    const request: CreateSignatureRequestInput = {
      title: 'Mutual NDA',
      signingOrder: true,
      remindersEnabled: true,
      reminderIntervalHours: 48,
      expiresAt: '2026-08-01T12:00:00.000Z',
      recipients: [
        {
          id: 'recipient-1',
          name: 'Ada Lovelace',
          email: 'ada@example.com',
          deliveryChannel: 'email',
          role: 'signer',
          signingOrder: 1,
          authenticationMethod: 'accessCode',
          accessCode: 'secret-code',
        },
      ],
      fields: [],
    };

    const formData = buildSignatureRequestFormData(file, request);

    expect(formData.get('file')).toBe(file);
    expect(JSON.parse(String(formData.get('request')))).toEqual(request);
  });
});
