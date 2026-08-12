import { BaseParameters } from '@app/types/parameters';
import { useBaseParameters, BaseParametersHook } from '@app/hooks/tools/shared/useBaseParameters';

export interface CertSignParameters extends BaseParameters {
  // Sign mode selection
  signMode: 'MANUAL' | 'AUTO' | 'KMS';
  // Certificate signing options. KMS uses certFile for the public signer certificate chain.
  certType: '' | 'PEM' | 'PKCS12' | 'PFX' | 'JKS';
  privateKeyFile?: File;
  certFile?: File;
  p12File?: File;
  jksFile?: File;
  password: string;
  kmsKeyId: string;
  kmsSignatureAlgorithm: 'SHA256_WITH_RSA' | 'SHA256_WITH_ECDSA';

  // Signature appearance options
  showSignature: boolean;
  reason: string;
  location: string;
  name: string;
  pageNumber: number;
  showLogo: boolean;
  signatureImage?: File;
  signatureText: string;
  signatureFieldName: string;
  padesProfile: 'B_B' | 'B_T' | 'B_LT' | 'B_LTA';
  tsaUrl: string;
}

export const defaultParameters: CertSignParameters = {
  signMode: 'MANUAL',
  certType: '',
  password: '',
  kmsKeyId: '',
  kmsSignatureAlgorithm: 'SHA256_WITH_RSA',
  showSignature: false,
  reason: '',
  location: '',
  name: '',
  pageNumber: 1,
  showLogo: true,
  signatureText: '',
  signatureFieldName: '',
  padesProfile: 'B_B',
  tsaUrl: '',
};

export type CertSignParametersHook = BaseParametersHook<CertSignParameters>;

export const useCertSignParameters = (): CertSignParametersHook => {
  return useBaseParameters({
    defaultParameters,
    endpointName: 'cert-sign',
    validateFn: (params) => {
      if (params.padesProfile !== 'B_B' && !params.tsaUrl.trim()) {
        return false;
      }
      // Auto mode (server certificate) - no additional validation needed
      if (params.signMode === 'AUTO') {
        return true;
      }

      if (params.signMode === 'KMS') {
        return !!params.certFile;
      }

      // Manual mode - requires certificate type and files
      if (!params.certType) {
        return false;
      }

      // Check for required files based on cert type
      switch (params.certType) {
        case 'PEM':
          return !!(params.privateKeyFile && params.certFile);
        case 'PKCS12':
        case 'PFX':
          return !!params.p12File;
        case 'JKS':
          return !!params.jksFile;
        default:
          return false;
      }
    },
  });
};
