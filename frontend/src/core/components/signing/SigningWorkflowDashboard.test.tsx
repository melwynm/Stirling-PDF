import { MantineProvider } from '@mantine/core';
import type { ReactNode } from 'react';
import { render, screen, waitFor } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { SigningWorkflowDashboard } from '@app/components/signing/SigningWorkflowDashboard';
import { PreferencesProvider } from '@app/contexts/PreferencesContext';
import { listSignatureRequests } from '@app/services/signingWorkflowService';

vi.mock('@app/services/signingWorkflowService', async importOriginal => {
  const original = await importOriginal<typeof import('@app/services/signingWorkflowService')>();
  return { ...original, listSignatureRequests: vi.fn() };
});

vi.mock('@app/components/shared/Tooltip', () => ({
  Tooltip: ({ children }: { children: ReactNode }) => children,
}));

describe('SigningWorkflowDashboard', () => {
  beforeEach(() => {
    vi.mocked(listSignatureRequests).mockResolvedValue([{
      modelVersion: 1,
      id: 'request-1',
      title: 'Mutual NDA',
      status: 'IN_PROGRESS',
      originalFilename: 'nda.pdf',
      documentRevision: 1,
      createdAt: '2026-08-12T10:00:00Z',
      updatedAt: '2026-08-12T11:00:00Z',
      signingOrder: true,
      remindersEnabled: true,
      reminderIntervalHours: 48,
      recipients: [{
        id: 'recipient-1',
        name: 'Ada Lovelace',
        email: 'ada@example.com',
        deliveryChannel: 'email',
        role: 'signer',
        signingOrder: 1,
        authenticationMethod: 'emailLink',
        status: 'SIGNED',
      }],
      fields: [],
    }]);
  });

  it('loads workflow status and progress for repeated operations', async () => {
    render(
      <MantineProvider>
        <PreferencesProvider><SigningWorkflowDashboard /></PreferencesProvider>
      </MantineProvider>,
    );

    await waitFor(() => expect(screen.getByText('Mutual NDA')).toBeInTheDocument());
    expect(screen.getByText('IN PROGRESS')).toBeInTheDocument();
    expect(screen.getByText('1/1')).toBeInTheDocument();
    expect(listSignatureRequests).toHaveBeenCalledWith(undefined);
  });
});
