import React from 'react';
import { act, renderHook } from '@testing-library/react';
import { beforeEach, describe, expect, test, vi } from 'vitest';
import { PreferencesProvider } from '@app/contexts/PreferencesContext';
import { useToolResources } from '@app/hooks/tools/shared/useToolResources';

const wrapper = ({ children }: { children: React.ReactNode }) => (
  <PreferencesProvider>{children}</PreferencesProvider>
);

describe('useToolResources', () => {
  beforeEach(() => {
    vi.mocked(URL.createObjectURL).mockReset();
    vi.mocked(URL.revokeObjectURL).mockReset();
  });

  test('revokes created download URLs on cleanup', async () => {
    vi.mocked(URL.createObjectURL).mockReturnValueOnce('blob:download-1');

    const { result } = renderHook(() => useToolResources(), { wrapper });
    const file = new File(['pdf'], 'split-page.pdf', { type: 'application/pdf' });

    await act(async () => {
      await result.current.createDownloadInfo([file], 'split');
    });

    act(() => {
      result.current.cleanupBlobUrls();
    });

    expect(URL.revokeObjectURL).toHaveBeenCalledTimes(1);
    expect(URL.revokeObjectURL).toHaveBeenCalledWith('blob:download-1');
  });

  test('revokes created download URLs on unmount', async () => {
    vi.mocked(URL.createObjectURL).mockReturnValueOnce('blob:download-2');

    const { result, unmount } = renderHook(() => useToolResources(), { wrapper });
    const file = new File(['pdf'], 'split-page.pdf', { type: 'application/pdf' });

    await act(async () => {
      await result.current.createDownloadInfo([file], 'split');
    });

    unmount();

    expect(URL.revokeObjectURL).toHaveBeenCalledTimes(1);
    expect(URL.revokeObjectURL).toHaveBeenCalledWith('blob:download-2');
  });
});
