import apiClient from '@app/services/apiClient';
import type {
  SigningAuthenticationMethod,
  SigningField,
  SigningRecipient,
  SigningRecipientRole,
} from '@app/types/signing';

export type SigningWorkflowStatus =
  | 'DRAFT'
  | 'SENT'
  | 'IN_PROGRESS'
  | 'COMPLETED'
  | 'CANCELLED'
  | 'DECLINED'
  | 'EXPIRED';

export interface SignatureRequestRecipientInput {
  id: string;
  name: string;
  email: string;
  role: SigningRecipientRole;
  signingOrder: number;
  authenticationMethod: SigningAuthenticationMethod;
  accessCode?: string;
}

export interface CreateSignatureRequestInput {
  title: string;
  message?: string;
  requesterName?: string;
  requesterEmail?: string;
  expiresAt?: string;
  signingOrder: boolean;
  remindersEnabled: boolean;
  reminderIntervalHours: number;
  recipients: SignatureRequestRecipientInput[];
  fields: SigningField[];
}

export interface SignatureRequestView {
  modelVersion: number;
  id: string;
  title: string;
  message?: string;
  requesterName?: string;
  requesterEmail?: string;
  status: SigningWorkflowStatus;
  originalFilename: string;
  documentRevision: number;
  createdAt: string;
  updatedAt: string;
  expiresAt?: string;
  signingOrder: boolean;
  remindersEnabled: boolean;
  reminderIntervalHours: number;
  recipients: SigningRecipient[];
  fields: SigningField[];
}

export const buildSignatureRequestFormData = (
  file: File,
  request: CreateSignatureRequestInput,
): FormData => {
  const formData = new FormData();
  formData.append('file', file);
  formData.append('request', JSON.stringify(request));
  return formData;
};

export const createSignatureRequest = async (
  file: File,
  request: CreateSignatureRequestInput,
): Promise<SignatureRequestView> => {
  const response = await apiClient.post<SignatureRequestView>(
    '/api/v1/security/e-sign/requests',
    buildSignatureRequestFormData(file, request),
    { headers: { 'Content-Type': 'multipart/form-data' } },
  );
  return response.data;
};
