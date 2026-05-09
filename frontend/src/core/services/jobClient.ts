import apiClient from '@app/services/apiClient';
import { getFilenameFromHeaders } from '@app/utils/fileResponseUtils';
import { processResponse, type ResponseHandler } from '@app/utils/toolResponseProcessor';

const ASYNC_FILE_SIZE_THRESHOLD_BYTES = 50 * 1024 * 1024;
const DEFAULT_POLL_INTERVAL_MS = 1000;
const DEFAULT_POLL_TIMEOUT_MS = 20 * 60 * 1000;

interface ResultFileMetadata {
  fileId: string;
  fileName?: string;
  contentType?: string;
}

interface JobResultPayload {
  jobId?: string;
  complete?: boolean;
  error?: string | null;
  result?: unknown;
}

interface JobStatusPayload extends JobResultPayload {
  jobResult?: JobResultPayload;
  queueInfo?: {
    inQueue?: boolean;
    position?: number;
  };
}

interface AsyncJobResponse {
  async?: boolean;
  jobId?: string;
  result?: unknown;
}

interface AsyncJobOptions {
  filePrefix?: string;
  preserveBackendFilename?: boolean;
  responseHandler?: ResponseHandler;
  pollIntervalMs?: number;
  pollTimeoutMs?: number;
  onStatus?: (status: string) => void;
}

function appendAsyncParam(endpoint: string): string {
  return endpoint.includes('?') ? `${endpoint}&async=true` : `${endpoint}?async=true`;
}

function getHeader(headers: Record<string, any> | undefined, name: string): string | undefined {
  if (!headers) return undefined;
  const direct = headers[name];
  if (typeof direct === 'string') return direct;
  const lower = headers[name.toLowerCase()];
  if (typeof lower === 'string') return lower;
  const getter = (headers as any).get;
  if (typeof getter === 'function') {
    const value = getter.call(headers, name);
    return typeof value === 'string' ? value : undefined;
  }
  return undefined;
}

async function parseJsonBlob(blob: Blob): Promise<any> {
  const text = await blob.text();
  return JSON.parse(text);
}

function normalizeJobStatus(payload: JobStatusPayload): {
  result: JobResultPayload;
  queuePosition?: number;
} {
  return {
    result: payload.jobResult ?? payload,
    queuePosition: payload.queueInfo?.inQueue ? payload.queueInfo.position : undefined,
  };
}

async function waitForJob(
  jobId: string,
  options: AsyncJobOptions,
): Promise<JobResultPayload> {
  const pollIntervalMs = options.pollIntervalMs ?? DEFAULT_POLL_INTERVAL_MS;
  const pollTimeoutMs = options.pollTimeoutMs ?? DEFAULT_POLL_TIMEOUT_MS;
  const startedAt = Date.now();

  while (Date.now() - startedAt < pollTimeoutMs) {
    const response = await apiClient.get<JobStatusPayload>(
      `/api/v1/general/job/${encodeURIComponent(jobId)}`,
      { suppressErrorToast: true },
    );

    const { result, queuePosition } = normalizeJobStatus(response.data);

    if (result.error) {
      options.onStatus?.('Failed');
      throw new Error(result.error);
    }

    if (result.complete) {
      options.onStatus?.('Completed');
      return result;
    }

    if (queuePosition !== undefined) {
      options.onStatus?.(`Queued (position ${queuePosition})`);
    } else {
      options.onStatus?.('Processing');
    }

    await new Promise(resolve => window.setTimeout(resolve, pollIntervalMs));
  }

  throw new Error(`Job ${jobId} did not complete within ${Math.round(pollTimeoutMs / 1000)} seconds`);
}

async function fileFromDownload(
  file: ResultFileMetadata,
  index: number,
  options: AsyncJobOptions,
): Promise<File> {
  const response = await apiClient.get<Blob>(
    `/api/v1/general/files/${encodeURIComponent(file.fileId)}`,
    {
      responseType: 'blob',
      suppressErrorToast: true,
    },
  );

  const contentDisposition = getHeader(response.headers, 'content-disposition');
  const backendName = getFilenameFromHeaders(contentDisposition) ?? file.fileName ?? `result-${index + 1}.pdf`;
  const filename =
    options.preserveBackendFilename || !options.filePrefix
      ? backendName
      : `${options.filePrefix}${backendName}`;
  const contentType =
    getHeader(response.headers, 'content-type') ??
    file.contentType ??
    response.data.type ??
    'application/octet-stream';

  return new File([response.data], filename, { type: contentType, lastModified: Date.now() });
}

async function processJobResult(
  jobId: string,
  originalFiles: File[],
  options: AsyncJobOptions,
): Promise<File[]> {
  const response = await apiClient.get<Blob>(
    `/api/v1/general/job/${encodeURIComponent(jobId)}/result`,
    {
      responseType: 'blob',
      suppressErrorToast: true,
    },
  );

  const contentType = getHeader(response.headers, 'content-type') ?? response.data.type;

  if (contentType?.includes('application/json')) {
    const payload = await parseJsonBlob(response.data);

    if (payload?.hasMultipleFiles && Array.isArray(payload.files)) {
      return await Promise.all(
        (payload.files as ResultFileMetadata[]).map((file, index) =>
          fileFromDownload(file, index, options),
        ),
      );
    }

    if (payload?.error || payload?.message) {
      throw new Error(String(payload.error ?? payload.message));
    }
  }

  return await processResponse(
    response.data,
    originalFiles,
    options.filePrefix,
    options.responseHandler,
    options.preserveBackendFilename ? response.headers : undefined,
  );
}

export function shouldUseAsyncJob(files: File[]): boolean {
  return files.reduce((total, file) => total + file.size, 0) >= ASYNC_FILE_SIZE_THRESHOLD_BYTES;
}

export async function submitAndWaitForJob(
  endpoint: string,
  formData: FormData,
  originalFiles: File[],
  options: AsyncJobOptions = {},
): Promise<File[]> {
  options.onStatus?.('Queued');

  const response = await apiClient.post<AsyncJobResponse>(
    appendAsyncParam(endpoint),
    formData,
    {
      responseType: 'json',
      suppressErrorToast: true,
    },
  );

  if (!response.data?.async || !response.data.jobId) {
    throw new Error('Server did not create an async job for this request');
  }

  await waitForJob(response.data.jobId, options);
  return await processJobResult(response.data.jobId, originalFiles, options);
}
