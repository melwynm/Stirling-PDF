import { describe, expect, test } from 'vitest';
import { shouldUseAsyncJob } from '@app/services/jobClient';

describe('jobClient large-file routing', () => {
  test('uses synchronous path below 50 MB', () => {
    const file = { name: 'small.pdf', size: 49 * 1024 * 1024 } as File;

    expect(shouldUseAsyncJob([file])).toBe(false);
  });

  test('uses async job path at 50 MB', () => {
    const file = { name: 'large.pdf', size: 50 * 1024 * 1024 } as File;

    expect(shouldUseAsyncJob([file])).toBe(true);
  });

  test('uses async job path when total request size reaches 50 MB', () => {
    const files = [
      { name: 'part-1.pdf', size: 25 * 1024 * 1024 },
      { name: 'part-2.pdf', size: 25 * 1024 * 1024 },
    ] as File[];

    expect(shouldUseAsyncJob(files)).toBe(true);
  });
});
