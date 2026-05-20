import { describe, expect, test, vi } from 'vitest';
import { consumeFiles } from '@app/contexts/file/fileActions';
import { FileLifecycleManager } from '@app/contexts/file/lifecycle';
import {
  createNewStirlingFileStub,
  createStirlingFile,
  FileContextAction,
  FileContextState,
  FileId,
} from '@app/types/fileContext';

vi.mock('@app/services/fileStorage', () => ({
  fileStorage: {
    markFileAsProcessed: vi.fn().mockResolvedValue(undefined),
    storeStirlingFile: vi.fn().mockResolvedValue(undefined),
  },
}));

const createState = (inputId: FileId, inputStub: ReturnType<typeof createNewStirlingFileStub>): FileContextState => ({
  files: {
    ids: [inputId],
    byId: {
      [inputId]: inputStub,
    },
  },
  pinnedFiles: new Set(),
  ui: {
    selectedFileIds: [inputId],
    selectedPageNumbers: [],
    isProcessing: false,
    processingProgress: 0,
    hasUnsavedChanges: false,
    errorFileIds: [],
  },
});

describe('fileActions consumeFiles', () => {
  test('cleans resources for consumed input files before state swap', async () => {
    const inputFile = new File(['input'], 'input.pdf', { type: 'application/pdf' });
    const inputId = 'input-file-id' as FileId;
    const inputStub = {
      ...createNewStirlingFileStub(inputFile, inputId, 'blob:input-thumbnail'),
      blobUrl: 'blob:input-file',
      processedFile: {
        totalPages: 1,
        pages: [{ pageNumber: 1, thumbnail: 'blob:input-page-thumbnail', rotation: 0, splitBefore: false }],
        thumbnailUrl: 'blob:input-processed-thumbnail',
        lastProcessed: Date.now(),
      },
    };

    const stateRef = { current: createState(inputId, inputStub) };
    const filesRef = { current: new Map<FileId, File>([[inputId, inputFile]]) };
    const dispatch = vi.fn<(action: FileContextAction) => void>();
    const lifecycleManager = new FileLifecycleManager(filesRef, dispatch);

    const outputFile = new File(['output'], 'output.pdf', { type: 'application/pdf' });
    const outputStub = createNewStirlingFileStub(outputFile, 'output-file-id' as FileId);
    const outputStirlingFile = createStirlingFile(outputFile, outputStub.id);

    await consumeFiles(
      [inputId],
      [outputStirlingFile],
      [outputStub],
      filesRef,
      dispatch,
      lifecycleManager,
      stateRef
    );

    expect(URL.revokeObjectURL).toHaveBeenCalledWith('blob:input-thumbnail');
    expect(URL.revokeObjectURL).toHaveBeenCalledWith('blob:input-file');
    expect(URL.revokeObjectURL).toHaveBeenCalledWith('blob:input-page-thumbnail');
    expect(filesRef.current.has(inputId)).toBe(false);
  });
});
