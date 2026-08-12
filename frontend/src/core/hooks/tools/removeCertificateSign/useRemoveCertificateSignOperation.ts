import { useTranslation } from 'react-i18next';
import { ToolType, useToolOperation } from '@app/hooks/tools/shared/useToolOperation';
import { createStandardErrorHandler } from '@app/utils/toolErrorHandler';
import { RemoveCertificateSignParameters, defaultParameters } from '@app/hooks/tools/removeCertificateSign/useRemoveCertificateSignParameters';

// Static function that can be used by both the hook and automation executor
export const buildRemoveCertificateSignFormData = (parameters: RemoveCertificateSignParameters, file: File): FormData => {
  const formData = new FormData();
  formData.append("fileInput", file);
  formData.append('mode', parameters.mode);
  formData.append('acknowledgeDestructive', parameters.acknowledgeDestructive.toString());
  if (parameters.mode === 'EXTRACT_REVISION' && parameters.signatureIndex > 0) {
    formData.append('signatureIndex', parameters.signatureIndex.toString());
  }
  return formData;
};

// Static configuration object
export const removeCertificateSignOperationConfig = {
  toolType: ToolType.singleFile,
  buildFormData: buildRemoveCertificateSignFormData,
  operationType: 'removeCertSign',
  endpoint: '/api/v1/security/remove-cert-sign',
  defaultParameters,
} as const;

export const useRemoveCertificateSignOperation = () => {
  const { t } = useTranslation();

  return useToolOperation<RemoveCertificateSignParameters>({
    ...removeCertificateSignOperationConfig,
    getErrorMessage: createStandardErrorHandler(t('removeCertSign.error.failed', 'An error occurred while removing certificate signatures.'))
  });
};
