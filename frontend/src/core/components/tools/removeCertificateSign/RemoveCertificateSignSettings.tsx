import React from 'react';
import { Alert, Checkbox, NumberInput, Select, Stack, Text } from '@mantine/core';
import { useTranslation } from 'react-i18next';
import { RemoveCertificateSignParameters } from '@app/hooks/tools/removeCertificateSign/useRemoveCertificateSignParameters';

interface RemoveCertificateSignSettingsProps {
  parameters: RemoveCertificateSignParameters;
  onParameterChange: <K extends keyof RemoveCertificateSignParameters>(parameter: K, value: RemoveCertificateSignParameters[K]) => void;
  disabled?: boolean;
}

const RemoveCertificateSignSettings: React.FC<RemoveCertificateSignSettingsProps> = ({ parameters, onParameterChange, disabled }) => {
  const { t } = useTranslation();

  return (
    <Stack gap="md">
      <Select
        label={t('removeCertSign.mode', 'Action')}
        value={parameters.mode}
        onChange={value => onParameterChange('mode', (value ?? 'EXTRACT_REVISION') as RemoveCertificateSignParameters['mode'])}
        data={[
          { value: 'EXTRACT_REVISION', label: t('removeCertSign.modes.extract', 'Extract signed revision') },
          { value: 'CLEAR_UNSIGNED_FIELDS', label: t('removeCertSign.modes.clearUnsigned', 'Clear unsigned signature fields') },
          { value: 'FLATTEN_SIGNATURES', label: t('removeCertSign.modes.flatten', 'Flatten signature fields') },
        ]}
        allowDeselect={false}
        disabled={disabled}
      />
      {parameters.mode === 'EXTRACT_REVISION' && (
        <>
          <Text size="sm" c="dimmed">
            {t('removeCertSign.extractDescription', 'Preserves the selected signed revision exactly as it existed when signed.')}
          </Text>
          <NumberInput
            label={t('removeCertSign.signatureIndex', 'Signature revision')}
            description={t('removeCertSign.signatureIndexHint', 'Leave at 0 to extract the latest signed revision.')}
            value={parameters.signatureIndex}
            onChange={value => onParameterChange('signatureIndex', Number(value) || 0)}
            min={0}
            step={1}
            disabled={disabled}
          />
        </>
      )}
      {parameters.mode === 'CLEAR_UNSIGNED_FIELDS' && (
        <Alert color="blue">
          {t('removeCertSign.clearUnsignedDescription', 'Only empty signature fields are removed. Existing signatures and revisions remain intact.')}
        </Alert>
      )}
      {parameters.mode === 'FLATTEN_SIGNATURES' && (
        <Alert color="red" title={t('removeCertSign.destructiveTitle', 'Destructive operation')}>
          <Stack gap="sm">
            <Text size="sm">
              {t('removeCertSign.destructiveDescription', 'This rewrites the PDF and invalidates cryptographic signature evidence. Use revision extraction when evidence must be preserved.')}
            </Text>
            <Checkbox
              label={t('removeCertSign.acknowledge', 'I understand that existing digital signatures will no longer validate')}
              checked={parameters.acknowledgeDestructive}
              onChange={event => onParameterChange('acknowledgeDestructive', event.currentTarget.checked)}
              disabled={disabled}
            />
          </Stack>
        </Alert>
      )}
    </Stack>
  );
};

export default RemoveCertificateSignSettings;
