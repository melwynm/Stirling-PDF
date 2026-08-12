import { describe, expect, test } from 'vitest';
import { buildCertSignFormData } from '@app/hooks/tools/certSign/useCertSignOperation';
import {
  CertSignParameters,
  defaultParameters,
} from '@app/hooks/tools/certSign/useCertSignParameters';

const pdfFile = new File(['pdf'], 'document.pdf', { type: 'application/pdf' });

const build = (parameters: Partial<CertSignParameters>) =>
  buildCertSignFormData({ ...defaultParameters, ...parameters }, pdfFile);

describe('buildCertSignFormData', () => {
  test('builds manual PEM signing fields', () => {
    const privateKeyFile = new File(['key'], 'signer.key');
    const certFile = new File(['certificate'], 'signer.pem');

    const formData = build({
      signMode: 'MANUAL',
      certType: 'PEM',
      privateKeyFile,
      certFile,
      password: 'secret',
    });

    expect(formData.get('fileInput')).toBe(pdfFile);
    expect(formData.get('certType')).toBe('PEM');
    expect(formData.get('privateKeyFile')).toBe(privateKeyFile);
    expect(formData.get('certFile')).toBe(certFile);
    expect(formData.get('password')).toBe('secret');
    expect(formData.get('kmsKeyId')).toBeNull();
  });

  test('builds server-certificate signing fields', () => {
    const formData = build({ signMode: 'AUTO' });

    expect(formData.get('fileInput')).toBe(pdfFile);
    expect(formData.get('certType')).toBe('SERVER');
    expect(formData.get('password')).toBeNull();
    expect(formData.get('certFile')).toBeNull();
  });

  test('builds KMS signing fields without private key material', () => {
    const certFile = new File(['certificate-chain'], 'signer-chain.pem');

    const formData = build({
      signMode: 'KMS',
      certFile,
      kmsKeyId: 'alias/pdf-signing',
      kmsSignatureAlgorithm: 'SHA256_WITH_ECDSA',
    });

    expect(formData.get('fileInput')).toBe(pdfFile);
    expect(formData.get('certType')).toBe('KMS');
    expect(formData.get('certFile')).toBe(certFile);
    expect(formData.get('kmsKeyId')).toBe('alias/pdf-signing');
    expect(formData.get('kmsSignatureAlgorithm')).toBe('SHA256_WITH_ECDSA');
    expect(formData.get('privateKeyFile')).toBeNull();
    expect(formData.get('password')).toBeNull();
  });

  test('includes visible-signature appearance fields only when enabled', () => {
    const hidden = build({ signMode: 'AUTO', showSignature: false });
    expect(hidden.get('showSignature')).toBeNull();

    const visible = build({
      signMode: 'AUTO',
      showSignature: true,
      reason: 'Approved',
      location: 'London',
      name: 'Ada Lovelace',
      pageNumber: 3,
      showLogo: false,
    });

    expect(visible.get('showSignature')).toBe('true');
    expect(visible.get('reason')).toBe('Approved');
    expect(visible.get('location')).toBe('London');
    expect(visible.get('name')).toBe('Ada Lovelace');
    expect(visible.get('pageNumber')).toBe('3');
    expect(visible.get('showLogo')).toBe('false');
  });
});
