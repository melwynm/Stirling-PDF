import { describe, expect, test } from 'vitest';
import { getWorkspaceToolOptions } from '@app/components/tools/workspace/ToolWorkspaceSwitcher';
import type { ToolRegistry, ToolRegistryEntry } from '@app/data/toolsTaxonomy';

const entry = (
  label: string,
  mode: NonNullable<ToolRegistryEntry['workspace']>['mode'],
  order: number
) =>
  ({
    component: () => null,
    workspace: { id: 'signing', mode, label, order },
  }) as unknown as ToolRegistryEntry;

describe('getWorkspaceToolOptions', () => {
  const registry = {
    sign: entry('Sign', 'fillAndSign', 10),
    certSign: entry('Certificate', 'certificate', 20),
    validateSignature: entry('Validate', 'validate', 40),
    manageCertificates: entry('Certificates', 'manageCertificates', 50),
    removeCertSign: entry('Remove', 'remove', 60),
  } as Partial<ToolRegistry>;

  test('returns the selected workspace tools in configured order', () => {
    const options = getWorkspaceToolOptions('validateSignature', registry);

    expect(options.map((option) => option.toolId)).toEqual([
      'sign',
      'certSign',
      'validateSignature',
      'manageCertificates',
      'removeCertSign',
    ]);
  });

  test('marks unavailable modes as disabled', () => {
    const options = getWorkspaceToolOptions('sign', registry, {
      certSign: { available: false, reason: 'disabledByAdmin' },
    });

    expect(options.find((option) => option.toolId === 'certSign')?.disabled).toBe(true);
    expect(options.find((option) => option.toolId === 'sign')?.disabled).toBe(false);
  });

  test('returns no modes for tools outside a workspace', () => {
    expect(getWorkspaceToolOptions('merge', registry)).toEqual([]);
  });
});
