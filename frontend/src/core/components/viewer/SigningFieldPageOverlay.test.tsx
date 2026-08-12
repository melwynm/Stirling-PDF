import { fireEvent, render, screen } from '@testing-library/react';
import { MantineProvider } from '@mantine/core';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { SigningFieldPageOverlay } from '@app/components/viewer/SigningFieldPageOverlay';
import { SIGNING_FIELD_DRAG_TYPE } from '@app/contexts/SigningFieldAuthoringContext';
import type { SigningField, SigningRecipient } from '@app/types/signing';

const mocks = vi.hoisted(() => ({
  addField: vi.fn(),
  updateField: vi.fn(),
  removeField: vi.fn(),
  fields: [] as SigningField[],
  recipients: [{
    id: 'recipient-1',
    name: 'Ada Lovelace',
    email: 'ada@example.com',
    role: 'signer',
    signingOrder: 1,
    authenticationMethod: 'emailLink',
  }] as SigningRecipient[],
}));

vi.mock('@app/contexts/SigningFieldAuthoringContext', async importOriginal => {
  const actual = await importOriginal<typeof import('@app/contexts/SigningFieldAuthoringContext')>();
  return {
    ...actual,
    useSigningFieldAuthoring: () => ({
      active: true,
      fields: mocks.fields,
      recipients: mocks.recipients,
      selectedRecipientId: 'recipient-1',
      placementType: null,
      addField: mocks.addField,
      updateField: mocks.updateField,
      removeField: mocks.removeField,
    }),
  };
});

describe('SigningFieldPageOverlay', () => {
  beforeEach(() => {
    mocks.fields = [];
    mocks.addField.mockReset();
    mocks.updateField.mockReset();
    mocks.removeField.mockReset();
  });

  it('creates normalized recipient-aware fields from a drop', () => {
    render(
      <MantineProvider>
        <SigningFieldPageOverlay pageIndex={2} pageWidth={1000} pageHeight={800} />
      </MantineProvider>,
    );
    const canvas = screen.getByLabelText('requestSignatures.fieldCanvas');
    vi.spyOn(canvas, 'getBoundingClientRect').mockReturnValue({
      x: 0,
      y: 0,
      top: 0,
      left: 0,
      right: 1000,
      bottom: 800,
      width: 1000,
      height: 800,
      toJSON: () => ({}),
    });

    const dropEvent = new Event('drop', { bubbles: true, cancelable: true });
    Object.defineProperties(dropEvent, {
      clientX: { value: 500 },
      clientY: { value: 400 },
      dataTransfer: {
        value: {
          types: [SIGNING_FIELD_DRAG_TYPE],
          getData: () => JSON.stringify({ type: 'signature', recipientId: 'recipient-1' }),
        },
      },
    });
    fireEvent(canvas, dropEvent);

    expect(mocks.addField).toHaveBeenCalledWith(expect.objectContaining({
      recipientId: 'recipient-1',
      type: 'signature',
      pageIndex: 2,
      bounds: expect.objectContaining({ x: 0.36, y: 0.45, width: 0.28, height: 0.1 }),
    }));
  });

  it('removes a focused field with the Delete key', () => {
    mocks.fields = [{
      id: 'field-1',
      recipientId: 'recipient-1',
      name: 'signature-1',
      label: 'Signature',
      type: 'signature',
      pageIndex: 0,
      bounds: { x: 0.1, y: 0.1, width: 0.3, height: 0.1 },
      required: true,
      readOnly: false,
      options: [],
    }];

    render(
      <MantineProvider>
        <SigningFieldPageOverlay pageIndex={0} pageWidth={1000} pageHeight={800} />
      </MantineProvider>,
    );
    fireEvent.keyDown(screen.getByRole('group', { name: 'Signature, Ada Lovelace' }), {
      key: 'Delete',
    });

    expect(mocks.removeField).toHaveBeenCalledWith('field-1');
  });
});
