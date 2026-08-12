/**
 * CertSignAutomationSettings - Used for automation only
 *
 * This component combines all certificate signing settings into a single step interface
 * for use in the automation system. It includes sign mode, certificate format, certificate files,
 * and signature appearance settings in one unified component.
 */

import { Stack } from "@mantine/core";
import { CertSignParameters } from "@app/hooks/tools/certSign/useCertSignParameters";
import CertificateTypeSettings from "@app/components/tools/certSign/CertificateTypeSettings";
import CertificateFormatSettings from "@app/components/tools/certSign/CertificateFormatSettings";
import CertificateFilesSettings from "@app/components/tools/certSign/CertificateFilesSettings";
import SignatureAppearanceSettings from "@app/components/tools/certSign/SignatureAppearanceSettings";

interface CertSignAutomationSettingsProps {
  parameters: CertSignParameters;
  onParameterChange: <K extends keyof CertSignParameters>(key: K, value: CertSignParameters[K]) => void;
  disabled?: boolean;
}

const CertSignAutomationSettings = ({ parameters, onParameterChange, disabled = false }: CertSignAutomationSettingsProps) => {
  return (
    <Stack gap="lg">
      {/* Sign Mode Selection */}
      <CertificateTypeSettings
        parameters={parameters}
        onParameterChange={onParameterChange}
        disabled={disabled}
      />

      {/* Certificate Format - only show for Manual mode */}
      {parameters.signMode === 'MANUAL' && (
        <CertificateFormatSettings
          parameters={parameters}
          onParameterChange={onParameterChange}
          disabled={disabled}
        />
      )}

      {/* Certificate Files - only show for modes that need uploaded certificate material */}
      {(parameters.signMode === 'MANUAL' || parameters.signMode === 'KMS') && (
        <CertificateFilesSettings
          parameters={parameters}
          onParameterChange={onParameterChange}
          disabled={disabled}
        />
      )}

      {/* Signature Appearance Settings */}
      <SignatureAppearanceSettings
        parameters={parameters}
        onParameterChange={onParameterChange}
        disabled={disabled}
      />
    </Stack>
  );
};

export default CertSignAutomationSettings;
