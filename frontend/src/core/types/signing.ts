export const SIGNING_MODEL_VERSION = 1 as const;

export type SigningRecipientRole = 'signer' | 'approver' | 'cc';
export type SigningAuthenticationMethod = 'emailLink' | 'accessCode';
export type SigningRecipientStatus =
  | 'PENDING'
  | 'SENT'
  | 'VIEWED'
  | 'SIGNED'
  | 'DECLINED'
  | 'EXPIRED';

export interface SigningRecipient {
  id: string;
  name: string;
  email: string;
  role: SigningRecipientRole;
  signingOrder: number;
  authenticationMethod: SigningAuthenticationMethod;
  authenticationConfigured?: boolean;
  status?: SigningRecipientStatus;
  metadata?: Record<string, string>;
}

export type SigningFieldType =
  | 'signature'
  | 'initials'
  | 'name'
  | 'email'
  | 'dateSigned'
  | 'text'
  | 'checkbox'
  | 'radio'
  | 'dropdown';

/** Bounds use the unrotated page's top-left origin and values from 0 through 1. */
export interface NormalizedSigningBounds {
  x: number;
  y: number;
  width: number;
  height: number;
}

export interface SigningField {
  id: string;
  recipientId: string;
  name: string;
  label: string;
  type: SigningFieldType;
  pageIndex: number;
  bounds: NormalizedSigningBounds;
  required: boolean;
  readOnly: boolean;
  placeholder?: string;
  defaultValue?: string;
  value?: string;
  completedAt?: string;
  options: string[];
  metadata?: Record<string, string>;
}

export interface SigningModelContract {
  modelVersion: typeof SIGNING_MODEL_VERSION;
  recipients: SigningRecipient[];
  fields: SigningField[];
}

export interface SigningModelValidationIssue {
  path: string;
  code: 'required' | 'duplicate' | 'invalid' | 'invalid_page' | 'invalid_bounds' | 'unknown_recipient';
  message: string;
}

const EDGE_EPSILON = 0.000000001;

export const isNormalizedSigningBounds = (bounds: NormalizedSigningBounds): boolean => {
  const values = [bounds.x, bounds.y, bounds.width, bounds.height];
  return values.every(Number.isFinite)
    && bounds.x >= 0
    && bounds.y >= 0
    && bounds.width > 0
    && bounds.height > 0
    && bounds.x + bounds.width <= 1 + EDGE_EPSILON
    && bounds.y + bounds.height <= 1 + EDGE_EPSILON;
};

export const validateSigningModel = (
  recipients: SigningRecipient[],
  fields: SigningField[],
): SigningModelValidationIssue[] => {
  const issues: SigningModelValidationIssue[] = [];
  const recipientIds = new Set<string>();

  if (recipients.length === 0) {
    issues.push({ path: 'recipients', code: 'required', message: 'At least one recipient is required' });
  }

  recipients.forEach((recipient, index) => {
    const path = `recipients[${index}]`;
    if (!recipient.id.trim()) {
      issues.push({ path: `${path}.id`, code: 'required', message: 'Recipient id is required' });
    } else if (recipientIds.has(recipient.id)) {
      issues.push({ path: `${path}.id`, code: 'duplicate', message: 'Recipient id must be unique' });
    } else {
      recipientIds.add(recipient.id);
    }
    if (!recipient.name.trim()) {
      issues.push({ path: `${path}.name`, code: 'required', message: 'Recipient name is required' });
    }
    if (!recipient.email.trim()) {
      issues.push({ path: `${path}.email`, code: 'required', message: 'Recipient email is required' });
    }
    if (!recipient.authenticationMethod) {
      issues.push({
        path: `${path}.authenticationMethod`,
        code: 'required',
        message: 'Recipient authentication method is required',
      });
    }
    if (!Number.isInteger(recipient.signingOrder) || recipient.signingOrder < 1) {
      issues.push({
        path: `${path}.signingOrder`,
        code: 'invalid',
        message: 'Signing order must be a positive integer',
      });
    }
  });

  const fieldIds = new Set<string>();
  const fieldNames = new Set<string>();
  fields.forEach((field, index) => {
    const path = `fields[${index}]`;
    if (!field.id.trim()) {
      issues.push({ path: `${path}.id`, code: 'required', message: 'Field id is required' });
    } else if (fieldIds.has(field.id)) {
      issues.push({ path: `${path}.id`, code: 'duplicate', message: 'Field id must be unique' });
    } else {
      fieldIds.add(field.id);
    }
    if (!field.name.trim()) {
      issues.push({ path: `${path}.name`, code: 'required', message: 'Field name is required' });
    } else if (fieldNames.has(field.name)) {
      issues.push({ path: `${path}.name`, code: 'duplicate', message: 'Field name must be unique' });
    } else {
      fieldNames.add(field.name);
    }
    if (!field.recipientId.trim()) {
      issues.push({
        path: `${path}.recipientId`,
        code: 'required',
        message: 'Field recipient id is required',
      });
    } else if (!recipientIds.has(field.recipientId)) {
      issues.push({
        path: `${path}.recipientId`,
        code: 'unknown_recipient',
        message: 'Field recipient does not exist',
      });
    }
    if (!Number.isInteger(field.pageIndex) || field.pageIndex < 0) {
      issues.push({
        path: `${path}.pageIndex`,
        code: 'invalid_page',
        message: 'Field page index must be zero or greater',
      });
    }
    if (!isNormalizedSigningBounds(field.bounds)) {
      issues.push({
        path: `${path}.bounds`,
        code: 'invalid_bounds',
        message: 'Field bounds must fit within the normalized page',
      });
    }
  });

  return issues;
};
