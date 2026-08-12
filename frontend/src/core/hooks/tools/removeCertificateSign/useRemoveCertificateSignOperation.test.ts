import { describe, expect, test } from 'vitest';
import { buildRemoveCertificateSignFormData } from '@app/hooks/tools/removeCertificateSign/useRemoveCertificateSignOperation';
import { defaultParameters } from '@app/hooks/tools/removeCertificateSign/useRemoveCertificateSignParameters';

describe('buildRemoveCertificateSignFormData', () => {
  const file = new File(['pdf'], 'signed.pdf', { type: 'application/pdf' });

  test('uses safe revision extraction by default', () => {
    const formData = buildRemoveCertificateSignFormData(defaultParameters, file);

    expect(formData.get('fileInput')).toBe(file);
    expect(formData.get('mode')).toBe('EXTRACT_REVISION');
    expect(formData.get('signatureIndex')).toBeNull();
    expect(formData.get('acknowledgeDestructive')).toBe('false');
  });

  test('includes an explicitly selected revision', () => {
    const formData = buildRemoveCertificateSignFormData(
      { ...defaultParameters, signatureIndex: 2 },
      file
    );

    expect(formData.get('signatureIndex')).toBe('2');
  });
});
