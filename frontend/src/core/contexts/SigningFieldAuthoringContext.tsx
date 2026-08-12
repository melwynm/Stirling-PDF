import {
  createContext,
  useCallback,
  useContext,
  useMemo,
  useState,
  type ReactNode,
} from 'react';
import { useFileContext } from '@app/contexts/FileContext';
import type { SigningField, SigningFieldType, SigningRecipient } from '@app/types/signing';

export const SIGNING_FIELD_DRAG_TYPE = 'application/x-stirling-signing-field';

interface SigningFieldAuthoringContextValue {
  active: boolean;
  fields: SigningField[];
  recipients: SigningRecipient[];
  selectedRecipientId: string | null;
  placementType: SigningFieldType | null;
  setActive: (active: boolean) => void;
  setRecipients: (recipients: SigningRecipient[]) => void;
  setSelectedRecipientId: (recipientId: string | null) => void;
  setPlacementType: (type: SigningFieldType | null) => void;
  addField: (field: SigningField) => void;
  updateField: (fieldId: string, updates: Partial<SigningField>) => void;
  removeField: (fieldId: string) => void;
  clearFields: () => void;
  markSaved: () => void;
}

const SigningFieldAuthoringContext = createContext<SigningFieldAuthoringContextValue | null>(null);

export const SigningFieldAuthoringProvider = ({ children }: { children: ReactNode }) => {
  const { setUnsavedChanges } = useFileContext();
  const [active, setActive] = useState(false);
  const [fields, setFields] = useState<SigningField[]>([]);
  const [recipients, setRecipientsState] = useState<SigningRecipient[]>([]);
  const [selectedRecipientId, setSelectedRecipientId] = useState<string | null>(null);
  const [placementType, setPlacementType] = useState<SigningFieldType | null>(null);

  const setRecipients = useCallback((nextRecipients: SigningRecipient[]) => {
    const assignableRecipientIds = new Set(
      nextRecipients.filter(recipient => recipient.role !== 'cc').map(recipient => recipient.id),
    );
    setRecipientsState(nextRecipients);
    setSelectedRecipientId(current => (
      current && assignableRecipientIds.has(current)
        ? current
        : nextRecipients.find(recipient => recipient.role !== 'cc')?.id ?? null
    ));
    setFields(current => {
      const nextFields = current.filter(field => assignableRecipientIds.has(field.recipientId));
      if (nextFields.length !== current.length) {
        setUnsavedChanges(true);
      }
      return nextFields;
    });
  }, [setUnsavedChanges]);

  const addField = useCallback((field: SigningField) => {
    setFields(current => [...current, field]);
    setUnsavedChanges(true);
  }, [setUnsavedChanges]);

  const updateField = useCallback((fieldId: string, updates: Partial<SigningField>) => {
    setFields(current => current.map(field => (
      field.id === fieldId ? { ...field, ...updates } : field
    )));
    setUnsavedChanges(true);
  }, [setUnsavedChanges]);

  const removeField = useCallback((fieldId: string) => {
    setFields(current => current.filter(field => field.id !== fieldId));
    setUnsavedChanges(true);
  }, [setUnsavedChanges]);

  const clearFields = useCallback(() => {
    setFields([]);
    setPlacementType(null);
    setUnsavedChanges(true);
  }, [setUnsavedChanges]);

  const markSaved = useCallback(() => setUnsavedChanges(false), [setUnsavedChanges]);

  const value = useMemo<SigningFieldAuthoringContextValue>(() => ({
    active,
    fields,
    recipients,
    selectedRecipientId,
    placementType,
    setActive,
    setRecipients,
    setSelectedRecipientId,
    setPlacementType,
    addField,
    updateField,
    removeField,
    clearFields,
    markSaved,
  }), [
    active,
    fields,
    recipients,
    selectedRecipientId,
    placementType,
    setRecipients,
    addField,
    updateField,
    removeField,
    clearFields,
    markSaved,
  ]);

  return (
    <SigningFieldAuthoringContext.Provider value={value}>
      {children}
    </SigningFieldAuthoringContext.Provider>
  );
};

export const useSigningFieldAuthoring = (): SigningFieldAuthoringContextValue => {
  const context = useContext(SigningFieldAuthoringContext);
  if (!context) {
    throw new Error('useSigningFieldAuthoring must be used within SigningFieldAuthoringProvider');
  }
  return context;
};
