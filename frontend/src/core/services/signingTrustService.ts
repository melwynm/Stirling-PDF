export interface SigningTrustCertificate {
  fingerprint: string;
  subject: string;
  issuer: string;
  serialNumber: string;
  validFrom: string;
  validUntil: string;
  certificateAuthority: boolean;
  importedAt: string;
}

const endpoint = '/api/v1/admin/signing-trust';

const readError = async (response: Response): Promise<string> => {
  const message = await response.text();
  return message || `Certificate management request failed (${response.status})`;
};

export const listSigningTrustCertificates = async (): Promise<SigningTrustCertificate[]> => {
  const response = await fetch(endpoint, { credentials: 'same-origin' });
  if (!response.ok) {
    throw new Error(await readError(response));
  }
  return response.json();
};

export const importSigningTrustCertificate = async (file: File): Promise<SigningTrustCertificate> => {
  const body = new FormData();
  body.append('file', file);
  const response = await fetch(endpoint, {
    method: 'POST',
    credentials: 'same-origin',
    body,
  });
  if (!response.ok) {
    throw new Error(await readError(response));
  }
  return response.json();
};

export const deleteSigningTrustCertificate = async (fingerprint: string): Promise<void> => {
  const response = await fetch(`${endpoint}/${encodeURIComponent(fingerprint)}`, {
    method: 'DELETE',
    credentials: 'same-origin',
  });
  if (!response.ok) {
    throw new Error(await readError(response));
  }
};

export const signingTrustCertificateUrl = (fingerprint: string): string =>
  `${endpoint}/${encodeURIComponent(fingerprint)}`;
