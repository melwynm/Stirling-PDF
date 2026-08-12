import { act, renderHook } from '@testing-library/react';
import { describe, expect, test } from 'vitest';
import {
  defaultParameters,
  useCertSignParameters,
} from '@app/hooks/tools/certSign/useCertSignParameters';

describe('useCertSignParameters', () => {
  test('initializes with the certificate-signing defaults', () => {
    const { result } = renderHook(() => useCertSignParameters());

    expect(result.current.parameters).toStrictEqual(defaultParameters);
    expect(result.current.getEndpointName()).toBe('cert-sign');
  });

  test('requires a certificate chain for KMS signing', () => {
    const { result } = renderHook(() => useCertSignParameters());

    act(() => {
      result.current.updateParameter('signMode', 'KMS');
    });
    expect(result.current.validateParameters()).toBe(false);

    act(() => {
      result.current.updateParameter(
        'certFile',
        new File(['certificate-chain'], 'signer-chain.pem')
      );
    });
    expect(result.current.validateParameters()).toBe(true);
  });

  test('keeps server signing file-free and manual signing format-aware', () => {
    const { result } = renderHook(() => useCertSignParameters());

    act(() => {
      result.current.updateParameter('signMode', 'AUTO');
    });
    expect(result.current.validateParameters()).toBe(true);

    act(() => {
      result.current.updateParameter('signMode', 'MANUAL');
      result.current.updateParameter('certType', 'PKCS12');
    });
    expect(result.current.validateParameters()).toBe(false);

    act(() => {
      result.current.updateParameter('p12File', new File(['keystore'], 'signer.p12'));
    });
    expect(result.current.validateParameters()).toBe(true);
  });

  test('requires a TSA for timestamped and long-term profiles', () => {
    const { result } = renderHook(() => useCertSignParameters());

    act(() => {
      result.current.updateParameter('signMode', 'AUTO');
      result.current.updateParameter('padesProfile', 'B_T');
    });
    expect(result.current.validateParameters()).toBe(false);

    act(() => {
      result.current.updateParameter('tsaUrl', 'https://tsa.example.test');
    });
    expect(result.current.validateParameters()).toBe(true);

    act(() => {
      result.current.updateParameter('padesProfile', 'B_LTA');
    });
    expect(result.current.validateParameters()).toBe(true);
  });
});
