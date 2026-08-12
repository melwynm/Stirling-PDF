import { useCallback } from 'react';
import { ActionIcon, Box, Text } from '@mantine/core';
import CloseRoundedIcon from '@mui/icons-material/CloseRounded';
import { Rnd } from 'react-rnd';
import { useTranslation } from 'react-i18next';
import {
  SIGNING_FIELD_DRAG_TYPE,
  useSigningFieldAuthoring,
} from '@app/contexts/SigningFieldAuthoringContext';
import type { SigningFieldType } from '@app/types/signing';

interface SigningFieldPageOverlayProps {
  pageIndex: number;
  pageWidth: number;
  pageHeight: number;
}

interface DraggedSigningField {
  type: SigningFieldType;
  recipientId: string;
}

const recipientColours = [
  { border: '#087f5b', background: '#d3f9d8', text: '#064e3b' },
  { border: '#1971c2', background: '#d0ebff', text: '#0b3d69' },
  { border: '#c2255c', background: '#ffdeeb', text: '#7a173a' },
  { border: '#5f3dc4', background: '#e5dbff', text: '#3b2780' },
  { border: '#e67700', background: '#ffec99', text: '#7c3d00' },
];

const fieldSize = (type: SigningFieldType) => {
  switch (type) {
    case 'checkbox':
      return { width: 0.055, height: 0.055 };
    case 'signature':
    case 'initials':
      return { width: 0.28, height: 0.1 };
    case 'text':
    case 'dropdown':
    case 'radio':
      return { width: 0.26, height: 0.065 };
    default:
      return { width: 0.22, height: 0.055 };
  }
};

const createFieldId = (): string => globalThis.crypto?.randomUUID?.()
  ?? `field-${Date.now()}-${Math.random().toString(16).slice(2)}`;

const clamp = (value: number, minimum: number, maximum: number) => (
  Math.min(Math.max(value, minimum), maximum)
);

export const SigningFieldPageOverlay = ({
  pageIndex,
  pageWidth,
  pageHeight,
}: SigningFieldPageOverlayProps) => {
  const { t } = useTranslation();
  const {
    active,
    fields,
    recipients,
    selectedRecipientId,
    placementType,
    addField,
    updateField,
    removeField,
  } = useSigningFieldAuthoring();

  const pageFields = fields.filter(field => field.pageIndex === pageIndex);

  const placeField = useCallback((
    type: SigningFieldType,
    recipientId: string,
    clientX: number,
    clientY: number,
    target: HTMLDivElement,
  ) => {
    const rect = target.getBoundingClientRect();
    const size = fieldSize(type);
    const x = clamp((clientX - rect.left) / rect.width - size.width / 2, 0, 1 - size.width);
    const y = clamp((clientY - rect.top) / rect.height - size.height / 2, 0, 1 - size.height);
    const id = createFieldId();
    const fieldNumber = fields.length + 1;
    addField({
      id,
      recipientId,
      name: `${type}-${fieldNumber}`,
      label: t(`requestSignatures.fieldTypes.${type}`, type),
      type,
      pageIndex,
      bounds: { x, y, ...size },
      required: true,
      readOnly: false,
      options: [],
    });
  }, [addField, fields.length, pageIndex, t]);

  if (!active || pageWidth <= 0 || pageHeight <= 0) {
    return null;
  }

  return (
    <div
      data-signing-field-page={pageIndex}
      style={{ position: 'absolute', inset: 0, zIndex: 30, touchAction: 'none' }}
      onClick={event => {
        if (!placementType || !selectedRecipientId || event.target !== event.currentTarget) return;
        placeField(
          placementType,
          selectedRecipientId,
          event.clientX,
          event.clientY,
          event.currentTarget,
        );
      }}
      onDragOver={event => {
        if (event.dataTransfer.types.includes(SIGNING_FIELD_DRAG_TYPE)) {
          event.preventDefault();
          event.dataTransfer.dropEffect = 'copy';
        }
      }}
      onDrop={event => {
        const rawPayload = event.dataTransfer.getData(SIGNING_FIELD_DRAG_TYPE);
        if (!rawPayload) return;
        event.preventDefault();
        event.stopPropagation();
        try {
          const payload = JSON.parse(rawPayload) as DraggedSigningField;
          placeField(payload.type, payload.recipientId, event.clientX, event.clientY, event.currentTarget);
        } catch {
          // Ignore malformed drag payloads from outside the application.
        }
      }}
      aria-label={t('requestSignatures.fieldCanvas', 'Signature field canvas')}
    >
      {pageFields.map(field => {
        const recipientIndex = Math.max(0, recipients.findIndex(recipient => recipient.id === field.recipientId));
        const recipient = recipients[recipientIndex];
        const colours = recipientColours[recipientIndex % recipientColours.length];
        return (
          <Rnd
            key={field.id}
            bounds="parent"
            minWidth={28}
            minHeight={24}
            size={{
              width: field.bounds.width * pageWidth,
              height: field.bounds.height * pageHeight,
            }}
            position={{
              x: field.bounds.x * pageWidth,
              y: field.bounds.y * pageHeight,
            }}
            onDragStop={(_event, data) => updateField(field.id, {
              bounds: {
                ...field.bounds,
                x: clamp(data.x / pageWidth, 0, 1 - field.bounds.width),
                y: clamp(data.y / pageHeight, 0, 1 - field.bounds.height),
              },
            })}
            onResizeStop={(_event, _direction, ref, _delta, position) => {
              const width = clamp(ref.offsetWidth / pageWidth, 0.02, 1);
              const height = clamp(ref.offsetHeight / pageHeight, 0.02, 1);
              updateField(field.id, {
                bounds: {
                  x: clamp(position.x / pageWidth, 0, 1 - width),
                  y: clamp(position.y / pageHeight, 0, 1 - height),
                  width,
                  height,
                },
              });
            }}
            style={{ zIndex: 31 }}
          >
            <Box
              role="group"
              tabIndex={0}
              aria-label={`${field.label}, ${recipient?.name ?? ''}`}
              onClick={event => event.stopPropagation()}
              onKeyDown={event => {
                if (event.key === 'Delete' || event.key === 'Backspace') {
                  event.preventDefault();
                  removeField(field.id);
                }
              }}
              style={{
                position: 'relative',
                width: '100%',
                height: '100%',
                display: 'flex',
                alignItems: 'center',
                justifyContent: 'center',
                overflow: 'hidden',
                border: `2px solid ${colours.border}`,
                background: colours.background,
                color: colours.text,
                borderRadius: '4px',
                cursor: 'move',
              }}
            >
              <Text size="xs" fw={600} ta="center" lineClamp={2} px={4}>
                {field.label}
              </Text>
              <ActionIcon
                variant="filled"
                color="red"
                size={18}
                radius="xl"
                aria-label={t('requestSignatures.removeField', 'Remove field')}
                onClick={event => {
                  event.stopPropagation();
                  removeField(field.id);
                }}
                style={{ position: 'absolute', top: 2, right: 2 }}
              >
                <CloseRoundedIcon style={{ fontSize: 14 }} />
              </ActionIcon>
            </Box>
          </Rnd>
        );
      })}
    </div>
  );
};
