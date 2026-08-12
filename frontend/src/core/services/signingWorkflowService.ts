import apiClient from '@app/services/apiClient';
import type {
  SigningAuthenticationMethod,
  SigningDeliveryChannel,
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
  | 'ARCHIVED'
  | 'DECLINED'
  | 'EXPIRED';

export interface SignatureRequestRecipientInput {
  id: string;
  name: string;
  email: string;
  phoneNumber?: string;
  deliveryChannel: SigningDeliveryChannel;
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
  archivedAt?: string;
  signingOrder: boolean;
  remindersEnabled: boolean;
  reminderIntervalHours: number;
  recipients: SignatureRequestRecipientInput[];
  fields: SigningField[];
}

export interface SignatureTemplateView {
  id: string;
  name: string;
  description?: string;
  originalFilename: string;
  createdAt: string;
  updatedAt: string;
  defaults: CreateSignatureRequestInput;
}

export interface CreateSignatureTemplateInput {
  name: string;
  description?: string;
  defaults: CreateSignatureRequestInput;
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

export const createSignatureTemplate = async (
  file: File,
  template: CreateSignatureTemplateInput,
): Promise<SignatureTemplateView> => {
  const formData = new FormData();
  formData.append('file', file);
  formData.append('template', JSON.stringify(template));
  const response = await apiClient.post<SignatureTemplateView>(
    '/api/v1/security/e-sign/templates',
    formData,
    { headers: { 'Content-Type': 'multipart/form-data' } },
  );
  return response.data;
};

export const listSignatureTemplates = async (): Promise<SignatureTemplateView[]> => {
  const response = await apiClient.get<SignatureTemplateView[]>('/api/v1/security/e-sign/templates');
  return response.data;
};

export const instantiateSignatureTemplate = async (
  templateId: string,
): Promise<SignatureRequestView> => {
  const response = await apiClient.post<SignatureRequestView>(
    `/api/v1/security/e-sign/templates/${templateId}/requests`,
    {},
  );
  return response.data;
};

export const deleteSignatureTemplate = async (templateId: string): Promise<void> => {
  await apiClient.delete(`/api/v1/security/e-sign/templates/${templateId}`);
};

export interface SignatureAuditEvent {
  id: string;
  requestId: string;
  recipientId?: string;
  type: string;
  actorName?: string;
  actorEmail?: string;
  message: string;
  timestamp: string;
  details: Record<string, string>;
}

export const listSignatureRequests = async (
  status?: SigningWorkflowStatus,
): Promise<SignatureRequestView[]> => {
  const response = await apiClient.get<SignatureRequestView[]>('/api/v1/security/e-sign/requests', {
    params: status ? { status } : undefined,
  });
  return response.data;
};

export const getSignatureAudit = async (requestId: string): Promise<SignatureAuditEvent[]> => {
  const response = await apiClient.get<SignatureAuditEvent[]>(
    `/api/v1/security/e-sign/requests/${requestId}/audit`,
  );
  return response.data;
};

export const cancelSignatureRequest = async (requestId: string): Promise<SignatureRequestView> => {
  const response = await apiClient.post<SignatureRequestView>(
    `/api/v1/security/e-sign/requests/${requestId}/cancel`,
    {},
  );
  return response.data;
};

export const archiveSignatureRequest = async (requestId: string): Promise<SignatureRequestView> => {
  const response = await apiClient.post<SignatureRequestView>(
    `/api/v1/security/e-sign/requests/${requestId}/archive`,
  );
  return response.data;
};

export const deleteSignatureRequest = async (requestId: string): Promise<void> => {
  await apiClient.delete(`/api/v1/security/e-sign/requests/${requestId}`);
};

export const remindSignatureRequest = async (requestId: string): Promise<void> => {
  await apiClient.post(`/api/v1/security/e-sign/requests/${requestId}/reminders`, {});
};

export const retrySignatureWebhooks = async (requestId: string): Promise<void> => {
  await apiClient.post(`/api/v1/security/e-sign/requests/${requestId}/webhooks/retry`);
};

export const signatureRequestDownloadUrl = (requestId: string): string => (
  `/api/v1/security/e-sign/requests/${requestId}/download`
);

export const signatureEvidenceDownloadUrl = (requestId: string): string => (
  `/api/v1/security/e-sign/requests/${requestId}/evidence.pdf`
);
