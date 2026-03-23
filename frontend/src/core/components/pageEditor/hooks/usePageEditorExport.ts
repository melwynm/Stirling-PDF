import { Dispatch, SetStateAction, useCallback } from "react";

import type {
  useFileActions,
  useFileState,
} from "@app/contexts/FileContext";
import { documentManipulationService } from "@app/services/documentManipulationService";
import { pdfExportService } from "@app/services/pdfExportService";
import { exportProcessedDocumentsToFiles } from "@app/services/pdfExportHelpers";
import { FileId } from "@app/types/file";
import { PDFDocument, PDFPage } from "@app/types/pageEditor";

type FileActions = ReturnType<typeof useFileActions>["actions"];
type FileSelectors = ReturnType<typeof useFileState>["selectors"];

interface UsePageEditorExportParams {
  displayDocument: PDFDocument | null;
  selectedPageIds: string[];
  splitPositions: Set<string>;
  selectedFileIds: FileId[];
  selectors: FileSelectors;
  actions: FileActions;
  setHasUnsavedChanges: (dirty: boolean) => void;
  exportLoading: boolean;
  setExportLoading: (loading: boolean) => void;
  setSplitPositions: Dispatch<SetStateAction<Set<string>>>;
  clearPersistedDocument: () => void;
  updateCurrentPages: (pages: PDFPage[] | null) => void;
}

const removePlaceholderPages = (document: PDFDocument): PDFDocument => {
  const filteredPages = document.pages.filter((page) => !page.isPlaceholder);
  if (filteredPages.length === document.pages.length) {
    return document;
  }

  const normalizedPages = filteredPages.map((page, index) => ({
    ...page,
    pageNumber: index + 1,
  }));

  return {
    ...document,
    pages: normalizedPages,
    totalPages: normalizedPages.length,
  };
};

const normalizeProcessedDocuments = (
  processed: PDFDocument | PDFDocument[]
): PDFDocument | PDFDocument[] => {
  if (Array.isArray(processed)) {
    const normalized = processed
      .map(removePlaceholderPages)
      .filter((doc) => doc.pages.length > 0);
    return normalized;
  }
  return removePlaceholderPages(processed);
};

export const usePageEditorExport = ({
  displayDocument,
  selectedPageIds,
  splitPositions,
  selectedFileIds,
  selectors,
  actions,
  setHasUnsavedChanges,
  exportLoading,
  setExportLoading,
  setSplitPositions,
  clearPersistedDocument,
  updateCurrentPages,
}: UsePageEditorExportParams) => {
  const getSourceFiles = useCallback((): Map<FileId, File> | null => {
    const sourceFiles = new Map<FileId, File>();

    selectedFileIds.forEach((fileId) => {
      const file = selectors.getFile(fileId);
      if (file) {
        sourceFiles.set(fileId, file);
      }
    });

    const hasInsertedFiles = false;
    const hasMultipleOriginalFiles = selectedFileIds.length > 1;

    if (!hasInsertedFiles && !hasMultipleOriginalFiles) {
      return null;
    }

    return sourceFiles.size > 0 ? sourceFiles : null;
  }, [selectedFileIds, selectors]);

  const getExportFilename = useCallback((): string => {
    if (selectedFileIds.length <= 1) {
      return displayDocument?.name || "document.pdf";
    }

    const firstFile = selectors.getFile(selectedFileIds[0]);
    if (firstFile) {
      const baseName = firstFile.name.replace(/\.pdf$/i, "");
      return `${baseName} (merged).pdf`;
    }

    return "merged-document.pdf";
  }, [selectedFileIds, selectors, displayDocument]);

  const onExportSelected = useCallback(async () => {
    if (!displayDocument || selectedPageIds.length === 0) return;

    setExportLoading(true);
    try {
      const processedDocuments =
        documentManipulationService.applyDOMChangesToDocument(
          displayDocument,
          displayDocument,
          splitPositions
        );

      const normalizedDocuments = normalizeProcessedDocuments(processedDocuments);
      const documentWithDOMState = Array.isArray(normalizedDocuments)
        ? normalizedDocuments[0]
        : normalizedDocuments;

      if (!documentWithDOMState || documentWithDOMState.pages.length === 0) {
        console.warn("Export skipped: no concrete pages available after filtering placeholders.");
        setExportLoading(false);
        return;
      }

      const validSelectedPageIds = selectedPageIds.filter((pageId) =>
        documentWithDOMState.pages.some((page) => page.id === pageId)
      );

      const sourceFiles = getSourceFiles();
      const exportFilename = getExportFilename();
      const result = sourceFiles
        ? await pdfExportService.exportPDFMultiFile(
            documentWithDOMState,
            sourceFiles,
            validSelectedPageIds,
            { selectedOnly: true, filename: exportFilename }
          )
        : await pdfExportService.exportPDF(
            documentWithDOMState,
            validSelectedPageIds,
            { selectedOnly: true, filename: exportFilename }
          );

      const downloadResult = await pdfExportService.downloadFile(result.blob, result.filename);
      if (downloadResult.cancelled) {
        return;
      }

      setHasUnsavedChanges(false);
      setSplitPositions(new Set());
    } catch (error) {
      console.error("Export failed:", error);
    } finally {
      setExportLoading(false);
    }
  }, [
    displayDocument,
    selectedPageIds,
    splitPositions,
    getSourceFiles,
    getExportFilename,
    setHasUnsavedChanges,
    setExportLoading,
  ]);

  const onExportAll = useCallback(async (forceNewFile = false) => {
    if (!displayDocument) return;

    setExportLoading(true);
    try {
      const processedDocuments =
        documentManipulationService.applyDOMChangesToDocument(
          displayDocument,
          displayDocument,
          splitPositions
        );

      const normalizedDocuments = normalizeProcessedDocuments(processedDocuments);

      if (
        (Array.isArray(normalizedDocuments) && normalizedDocuments.length === 0) ||
        (!Array.isArray(normalizedDocuments) && normalizedDocuments.pages.length === 0)
      ) {
        console.warn("Export skipped: no concrete pages available after filtering placeholders.");
        setExportLoading(false);
        return;
      }

      const sourceFiles = getSourceFiles();
      const exportFilename = getExportFilename();
      const sourceStub = selectedFileIds.length === 1
        ? selectors.getStirlingFileStub(selectedFileIds[0])
        : undefined;
      const files = await exportProcessedDocumentsToFiles(
        normalizedDocuments,
        sourceFiles,
        exportFilename
      );

      if (files.length > 1) {
        const JSZip = await import("jszip");
        const zip = new JSZip.default();

        files.forEach((file) => {
          zip.file(file.name, file);
        });

        const zipBlob = await zip.generateAsync({ type: "blob" });
        const zipFilename = exportFilename.replace(/\.pdf$/i, ".zip");

        const downloadResult = await pdfExportService.downloadFile(zipBlob, zipFilename);
        if (downloadResult.cancelled) {
          return;
        }
      } else {
        const file = files[0];
        const downloadResult = await pdfExportService.downloadFile(
          file,
          file.name,
          forceNewFile ? undefined : sourceStub?.localFilePath
        );
        if (downloadResult.cancelled) {
          return;
        }
      }

      setHasUnsavedChanges(false);
      setSplitPositions(new Set());
    } catch (error) {
      console.error("Export failed:", error);
    } finally {
      setExportLoading(false);
    }
  }, [
    displayDocument,
    splitPositions,
    getSourceFiles,
    getExportFilename,
    selectedFileIds,
    selectors,
    setHasUnsavedChanges,
    setExportLoading,
  ]);

  const applyChanges = useCallback(async () => {
    if (!displayDocument) return;

    setExportLoading(true);
    try {
      const processedDocuments =
        documentManipulationService.applyDOMChangesToDocument(
          displayDocument,
          displayDocument,
          splitPositions
        );

      const normalizedDocuments = normalizeProcessedDocuments(processedDocuments);

      if (
        (Array.isArray(normalizedDocuments) && normalizedDocuments.length === 0) ||
        (!Array.isArray(normalizedDocuments) && normalizedDocuments.pages.length === 0)
      ) {
        console.warn("Apply changes skipped: no concrete pages available after filtering placeholders.");
        setExportLoading(false);
        return;
      }

      const sourceFiles = getSourceFiles();
      const exportFilename = getExportFilename();
      const files = await exportProcessedDocumentsToFiles(
        normalizedDocuments,
        sourceFiles,
        exportFilename
      );

      // Add "_multitool" suffix to filenames
      const renamedFiles = files.map(file => {
        const nameParts = file.name.match(/^(.+?)(\.pdf)$/i);
        if (nameParts) {
          const baseName = nameParts[1];
          const extension = nameParts[2];
          const newName = `${baseName}_multitool${extension}`;
          return new File([file], newName, { type: file.type });
        }
        return file;
      });

      // Store source file IDs before adding new files
      const sourceFileIds = [...selectedFileIds];
      const sourceStub = sourceFileIds.length === 1
        ? selectors.getStirlingFileStub(sourceFileIds[0])
        : undefined;

      const newStirlingFiles = await actions.addFiles(renamedFiles, {
        selectFiles: true,
      });
      if (newStirlingFiles.length === 0) {
        throw new Error("Apply changes did not create any replacement files.");
      }

      // Clear cached page state before swapping the UI over to the replacement files.
      clearPersistedDocument();
      updateCurrentPages(null);

      if (newStirlingFiles.length > 0) {
        actions.setSelectedFiles(newStirlingFiles.map((file) => file.fileId));
      }

      if (sourceStub?.localFilePath && newStirlingFiles.length === 1) {
        actions.updateStirlingFileStub(newStirlingFiles[0].fileId, {
          localFilePath: sourceStub.localFilePath,
          isDirty: true
        });
      }

      // Only remove the originals after replacements have been added successfully.
      if (sourceFileIds.length > 0) {
        await actions.removeFiles(sourceFileIds, true);
      }

      setHasUnsavedChanges(false);
      setSplitPositions(new Set());
    } catch (error) {
      console.error("Apply changes failed:", error);
    } finally {
      setExportLoading(false);
    }
  }, [
    displayDocument,
    splitPositions,
    getSourceFiles,
    getExportFilename,
    actions,
    selectedFileIds,
    selectors,
    setHasUnsavedChanges,
    setExportLoading,
    clearPersistedDocument,
    updateCurrentPages,
  ]);

  return {
    exportLoading,
    onExportSelected,
    onExportAll,
    applyChanges,
  };
};

export type UsePageEditorExportReturn = ReturnType<typeof usePageEditorExport>;
