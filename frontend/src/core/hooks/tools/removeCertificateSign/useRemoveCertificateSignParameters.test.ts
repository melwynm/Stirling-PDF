import { act, renderHook } from '@testing-library/react';
import { describe, expect, test } from 'vitest';
import { useRemoveCertificateSignParameters } from '@app/hooks/tools/removeCertificateSign/useRemoveCertificateSignParameters';

describe('useRemoveCertificateSignParameters', () => {
  test('requires acknowledgement for destructive flattening', () => {
    const { result } = renderHook(() => useRemoveCertificateSignParameters());

    act(() => result.current.updateParameter('mode', 'FLATTEN_SIGNATURES'));
    expect(result.current.validateParameters()).toBe(false);

    act(() => result.current.updateParameter('acknowledgeDestructive', true));
    expect(result.current.validateParameters()).toBe(true);
  });
});
