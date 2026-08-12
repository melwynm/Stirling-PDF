import { describe, expect, test } from 'vitest';
import { normalizeBackendResult } from '@app/hooks/tools/validateSignature/utils/signatureUtils';
import type { SignatureValidationBackendResult } from '@app/types/validateSignature';
import type { StirlingFile } from '@app/types/fileContext';

describe('normalizeBackendResult', () => {
  test('preserves signed revision metadata', () => {
    const backend = {
      valid: true,
      chainValid: true,
      trustValid: true,
      notExpired: true,
      revisionNumber: 2,
      revisionLength: 12_345,
      coversWholeDocument: false,
      laterRevisionsPresent: true,
    } satisfies SignatureValidationBackendResult;
    const file = { fileId: 'file-1' } as StirlingFile;

    const result = normalizeBackendResult(backend, file, 1);

    expect(result).toMatchObject({
      revisionNumber: 2,
      revisionLength: 12_345,
      coversWholeDocument: false,
      laterRevisionsPresent: true,
    });
  });
});
