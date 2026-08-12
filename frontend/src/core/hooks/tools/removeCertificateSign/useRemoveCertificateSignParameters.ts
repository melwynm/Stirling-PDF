import { BaseParameters } from '@app/types/parameters';
import { useBaseParameters, BaseParametersHook } from '@app/hooks/tools/shared/useBaseParameters';

export interface RemoveCertificateSignParameters extends BaseParameters {
  mode: 'EXTRACT_REVISION' | 'CLEAR_UNSIGNED_FIELDS' | 'FLATTEN_SIGNATURES';
  signatureIndex: number;
  acknowledgeDestructive: boolean;
}

export const defaultParameters: RemoveCertificateSignParameters = {
  mode: 'EXTRACT_REVISION',
  signatureIndex: 0,
  acknowledgeDestructive: false,
};

export type RemoveCertificateSignParametersHook = BaseParametersHook<RemoveCertificateSignParameters>;

export const useRemoveCertificateSignParameters = (): RemoveCertificateSignParametersHook => {
  return useBaseParameters({
    defaultParameters,
    endpointName: 'remove-cert-sign',
    validateFn: parameters =>
      parameters.mode !== 'FLATTEN_SIGNATURES' || parameters.acknowledgeDestructive,
  });
};
