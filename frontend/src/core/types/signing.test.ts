import { describe, expect, it } from 'vitest';
import {
  SIGNING_MODEL_VERSION,
  SigningField,
  SigningRecipient,
  isNormalizedSigningBounds,
  validateSigningModel,
} from '@app/types/signing';

const recipients: SigningRecipient[] = [
  {
    id: 'recipient-1',
    name: 'Ada Lovelace',
    email: 'ada@example.com',
    deliveryChannel: 'email',
    role: 'signer',
    signingOrder: 1,
    authenticationMethod: 'emailLink',
  },
];

const fields: SigningField[] = [
  {
    id: 'field-1',
    recipientId: 'recipient-1',
    name: 'customer-signature',
    label: 'Signature',
    type: 'signature',
    pageIndex: 0,
    bounds: { x: 0.55, y: 0.75, width: 0.35, height: 0.12 },
    required: true,
    readOnly: false,
    options: [],
  },
];

describe('signing model contract', () => {
  it('accepts recipient-aware normalized fields', () => {
    expect(SIGNING_MODEL_VERSION).toBe(1);
    expect(validateSigningModel(recipients, fields)).toEqual([]);
  });

  it('rejects fields outside the page', () => {
    expect(isNormalizedSigningBounds({ x: 0.8, y: 0.2, width: 0.3, height: 0.1 })).toBe(false);

    const issues = validateSigningModel(recipients, [
      { ...fields[0], bounds: { x: 0.8, y: 0.2, width: 0.3, height: 0.1 } },
    ]);

    expect(issues).toContainEqual(expect.objectContaining({ path: 'fields[0].bounds', code: 'invalid_bounds' }));
  });

  it('reports duplicate identities and unknown recipients', () => {
    const issues = validateSigningModel(
      [...recipients, { ...recipients[0], name: 'Second signer' }],
      [{ ...fields[0], recipientId: 'missing-recipient' }, { ...fields[0] }],
    );

    expect(issues).toEqual(expect.arrayContaining([
      expect.objectContaining({ path: 'recipients[1].id', code: 'duplicate' }),
      expect.objectContaining({ path: 'fields[0].recipientId', code: 'unknown_recipient' }),
      expect.objectContaining({ path: 'fields[1].id', code: 'duplicate' }),
      expect.objectContaining({ path: 'fields[1].name', code: 'duplicate' }),
    ]));
  });
});
