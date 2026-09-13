import { MantineProvider } from '@mantine/core';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import RecipientSigningPage from '@app/pages/RecipientSigningPage';
import {
  completeRecipientSignature,
  declineRecipientSignature,
  downloadRecipientDocument,
  getRecipientSigningContext,
  markRecipientViewed,
  type RecipientSigningContext,
} from '@app/services/recipientSigningService';

vi.mock('@app/services/recipientSigningService', () => ({
  completeRecipientSignature: vi.fn(), declineRecipientSignature: vi.fn(),
  downloadRecipientDocument: vi.fn(), getRecipientSigningContext: vi.fn(), markRecipientViewed: vi.fn(),
}));

vi.mock('react-i18next', () => {
  const t = (key: string, fallback?: string) => fallback ?? key;
  return { useTranslation: () => ({ t }) };
});

const context = (): RecipientSigningContext => {
  const recipient = {
    id: 'recipient-1', name: 'Ada', email: 'ada@example.test', role: 'signer' as const,
    deliveryChannel: 'email' as const, signingOrder: 1, authenticationMethod: 'emailLink' as const,
    status: 'SENT' as const,
  };
  return {
    recipient, downloadUrl: '/document',
    request: {
      modelVersion: 1, id: 'request-1', title: 'Agreement', status: 'SENT',
      originalFilename: 'agreement.pdf', documentRevision: 0, createdAt: '', updatedAt: '',
      signingOrder: false, remindersEnabled: false, reminderIntervalHours: 48,
      recipients: [recipient], fields: [{
        id: 'signature-1', name: 'signature', label: 'Your signature', type: 'signature',
        recipientId: recipient.id, pageIndex: 0, bounds: { x: 0.1, y: 0.1, width: 0.3, height: 0.1 },
        required: true, readOnly: false, options: [],
      }],
    },
  };
};

const showPage = () => render(
  <MantineProvider><MemoryRouter initialEntries={['/sign/token']}>
    <Routes><Route path="/sign/:token" element={<RecipientSigningPage />} /></Routes>
  </MemoryRouter></MantineProvider>,
);

describe('Recipient signing outcomes', () => {
  beforeEach(() => {
    vi.resetAllMocks();
    vi.mocked(getRecipientSigningContext).mockResolvedValue(context());
    vi.mocked(downloadRecipientDocument).mockResolvedValue(new Blob(['pdf']));
    vi.mocked(markRecipientViewed).mockResolvedValue(undefined);
    vi.spyOn(URL, 'createObjectURL').mockReturnValue('blob:document');
    vi.spyOn(URL, 'revokeObjectURL').mockImplementation(() => undefined);
  });

  it('keeps signing success when the updated PDF download fails', async () => {
    vi.mocked(completeRecipientSignature).mockResolvedValue({ ...context().request, status: 'COMPLETED' });
    vi.mocked(downloadRecipientDocument).mockResolvedValueOnce(new Blob(['original']))
      .mockRejectedValueOnce(new Error('Network unavailable'));
    showPage();
    await screen.findByRole('link', { name: 'Download' });
    fireEvent.click(screen.getByRole('checkbox', { name: /I agree/ }));
    fireEvent.click(screen.getByRole('button', { name: 'Sign' }));
    expect(await screen.findByText('Signature recorded')).toBeInTheDocument();
    expect(await screen.findByText(/updated PDF could not be downloaded/)).toBeInTheDocument();
    expect(screen.queryByText('Unable to complete the signature')).not.toBeInTheDocument();
    expect(screen.queryByRole('link', { name: 'Download' })).not.toBeInTheDocument();
    expect(completeRecipientSignature).toHaveBeenCalledTimes(1);
  });

  it('shows a distinct outcome for a declined request', async () => {
    vi.mocked(declineRecipientSignature).mockResolvedValue({ ...context().request, status: 'DECLINED' });
    showPage();
    await screen.findByRole('link', { name: 'Download' });
    fireEvent.click(screen.getByRole('button', { name: 'Decline' }));
    fireEvent.click(screen.getByRole('button', { name: 'Decline' }));
    expect(await screen.findByText('Request declined')).toBeInTheDocument();
    expect(screen.queryByText('Signature recorded')).not.toBeInTheDocument();
    expect(completeRecipientSignature).not.toHaveBeenCalled();
  });

  it('does not offer signing again when reopening an already signed response', async () => {
    const signed = context();
    signed.recipient.status = 'SIGNED';
    vi.mocked(getRecipientSigningContext).mockResolvedValue(signed);
    showPage();
    expect(await screen.findByText('Signature recorded')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Sign' })).not.toBeInTheDocument();
    expect(markRecipientViewed).not.toHaveBeenCalled();
  });

  it('requires a document download before accepting a signature', async () => {
    vi.mocked(downloadRecipientDocument).mockRejectedValue(new Error('Offline'));
    showPage();
    await screen.findByText('Unable to open the document');
    fireEvent.click(screen.getByRole('checkbox', { name: /I agree/ }));
    expect(screen.getByRole('button', { name: 'Sign' })).toBeDisabled();
  });

  it('moves focus to a missing required field', async () => {
    const withText = context();
    withText.request.fields[0] = { ...withText.request.fields[0], type: 'text', label: 'Reference' };
    vi.mocked(getRecipientSigningContext).mockResolvedValue(withText);
    showPage();
    await screen.findByRole('textbox', { name: /Reference/ });
    fireEvent.click(screen.getByRole('button', { name: 'Next required field' }));
    expect(screen.getByRole('textbox', { name: /Reference/ })).toHaveFocus();
    fireEvent.change(screen.getByRole('textbox', { name: /Reference/ }), { target: { value: 'ABC' } });
    await waitFor(() => expect(screen.queryByRole('button', { name: 'Next required field' })).not.toBeInTheDocument());
  });
});
