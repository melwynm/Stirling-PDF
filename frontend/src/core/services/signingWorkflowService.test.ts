import { describe, expect, it } from 'vitest';
import {
  buildSignatureRequestFormData,
  createSignatureTemplate,
  listSignatureTemplates,
  type CreateSignatureRequestInput,
} from '@app/services/signingWorkflowService';
import apiClient from '@app/services/apiClient';
import { vi } from 'vitest';

vi.mock('@app/services/apiClient', () => ({
  default: { post: vi.fn(), get: vi.fn(), delete: vi.fn() },
}));

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

  it('creates templates using the multipart template contract', async () => {
    const file = new File(['%PDF-1.7'], 'contract.pdf', { type: 'application/pdf' });
    const template = {
      name: 'Mutual NDA',
      defaults: {
        title: 'Mutual NDA',
        signingOrder: false,
        remindersEnabled: true,
        reminderIntervalHours: 48,
        recipients: [],
        fields: [],
      },
    };
    vi.mocked(apiClient.post).mockResolvedValue({ data: { id: 'template-1' } });

    await createSignatureTemplate(file, template);

    const formData = vi.mocked(apiClient.post).mock.calls[0][1] as FormData;
    expect(formData.get('file')).toBe(file);
    expect(JSON.parse(String(formData.get('template')))).toEqual(template);
  });

  it('lists templates from the owner-scoped endpoint', async () => {
    vi.mocked(apiClient.get).mockResolvedValue({ data: [] });

    await expect(listSignatureTemplates()).resolves.toEqual([]);
    expect(apiClient.get).toHaveBeenCalledWith('/api/v1/security/e-sign/templates');
  });
});
