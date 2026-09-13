import apiClient from '@app/services/apiClient';
import type { SignatureRequestView } from '@app/services/signingWorkflowService';
import type { SigningRecipient } from '@app/types/signing';

export interface RecipientSigningContext {
  request: SignatureRequestView;
  recipient: SigningRecipient;
  downloadUrl: string;
}

export interface RecipientSignInput {
  signerName: string;
  signatureType: 'typed' | 'drawn';
  signatureDataUrl?: string;
  accessCode?: string;
  otp?: string;
  consentAccepted: boolean;
  consentText: string;
  fieldValues: Record<string, string>;
}

export interface SigningOtpChallenge {
  expiresAt: string;
  resendAt: string;
}

export const requestSigningOtp = async (token: string, input: RecipientSignInput): Promise<SigningOtpChallenge> => {
  const response = await apiClient.post<SigningOtpChallenge>(
    `/api/v1/security/e-sign/recipients/${encodeURIComponent(token)}/otp`, input,
  );
  return response.data;
};

const accessCodeHeaders = (accessCode?: string) => (
  accessCode ? { 'X-Signing-Access-Code': accessCode } : undefined
);

export const getRecipientSigningContext = async (
  token: string,
): Promise<RecipientSigningContext> => {
  const response = await apiClient.get<RecipientSigningContext>(
    `/api/v1/security/e-sign/recipients/${encodeURIComponent(token)}`,
  );
  return response.data;
};

export const downloadRecipientDocument = async (
  token: string,
  accessCode?: string,
): Promise<Blob> => {
  const response = await apiClient.get(
    `/api/v1/security/e-sign/recipients/${encodeURIComponent(token)}/download`,
    { responseType: 'blob', headers: accessCodeHeaders(accessCode) },
  );
  return response.data as Blob;
};

export const markRecipientViewed = async (
  token: string,
  accessCode?: string,
): Promise<void> => {
  await apiClient.post(
    `/api/v1/security/e-sign/recipients/${encodeURIComponent(token)}/viewed`,
    undefined,
    { headers: accessCodeHeaders(accessCode) },
  );
};

export const completeRecipientSignature = async (
  token: string,
  input: RecipientSignInput,
): Promise<SignatureRequestView> => {
  const response = await apiClient.post(
    `/api/v1/security/e-sign/recipients/${encodeURIComponent(token)}/sign`,
    input,
  );
  return response.data.request as SignatureRequestView;
};

export const declineRecipientSignature = async (
  token: string,
  reason: string,
  accessCode?: string,
): Promise<SignatureRequestView> => {
  const response = await apiClient.post(
    `/api/v1/security/e-sign/recipients/${encodeURIComponent(token)}/decline`,
    { reason, accessCode },
  );
  return response.data.request as SignatureRequestView;
};
