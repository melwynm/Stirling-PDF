import { MantineProvider } from '@mantine/core';
import { render, screen, waitFor } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { SigningTemplateDashboard } from '@app/components/signing/SigningTemplateDashboard';
import { listSignatureTemplates } from '@app/services/signingWorkflowService';

vi.mock('@app/services/signingWorkflowService', async importOriginal => {
  const original = await importOriginal<typeof import('@app/services/signingWorkflowService')>();
  return { ...original, listSignatureTemplates: vi.fn() };
});

vi.mock('@app/components/shared/Tooltip', () => ({
  Tooltip: ({ children }: { children: React.ReactNode }) => children,
}));

describe('SigningTemplateDashboard', () => {
  beforeEach(() => {
    vi.mocked(listSignatureTemplates).mockResolvedValue([{
      id: 'template-1',
      name: 'Mutual NDA',
      originalFilename: 'nda.pdf',
      createdAt: '2026-08-12T10:00:00Z',
      updatedAt: '2026-08-12T11:00:00Z',
      defaults: {
        title: 'Mutual NDA',
        signingOrder: false,
        remindersEnabled: true,
        reminderIntervalHours: 48,
        recipients: [],
        fields: [],
      },
    }]);
  });

  it('shows reusable templates and recipient counts', async () => {
    render(<MantineProvider><SigningTemplateDashboard /></MantineProvider>);

    await waitFor(() => expect(screen.getByText('Mutual NDA')).toBeInTheDocument());
    expect(screen.getByText('nda.pdf')).toBeInTheDocument();
    expect(screen.getByText('0')).toBeInTheDocument();
  });
});
