import { useCallback, useEffect, useMemo, useState } from 'react';
import { useParams } from 'react-router-dom';
import {
  Alert,
  Button,
  Checkbox,
  Group,
  Loader,
  Paper,
  PasswordInput,
  Stack,
  Text,
  Textarea,
  TextInput,
  Title,
} from '@mantine/core';
import CheckCircleRoundedIcon from '@mui/icons-material/CheckCircleRounded';
import DownloadRoundedIcon from '@mui/icons-material/DownloadRounded';
import LockOpenRoundedIcon from '@mui/icons-material/LockOpenRounded';
import SendRoundedIcon from '@mui/icons-material/SendRounded';
import { useTranslation } from 'react-i18next';
import {
  completeRecipientSignature,
  declineRecipientSignature,
  downloadRecipientDocument,
  getRecipientSigningContext,
  markRecipientViewed,
  type RecipientSigningContext,
} from '@app/services/recipientSigningService';
import type { SigningField } from '@app/types/signing';
import styles from '@app/pages/RecipientSigningPage.module.css';

const initialsFor = (name: string): string => name
  .trim()
  .split(/\s+/)
  .filter(Boolean)
  .slice(0, 3)
  .map(part => part[0]?.toUpperCase() ?? '')
  .join('');

const initialFieldValues = (context: RecipientSigningContext): Record<string, string> => (
  Object.fromEntries(context.request.fields.map(field => {
    const value = field.defaultValue ?? (() => {
      switch (field.type) {
        case 'signature':
        case 'name':
          return context.recipient.name;
        case 'initials':
          return initialsFor(context.recipient.name);
        case 'email':
          return context.recipient.email;
        case 'dateSigned':
          return new Date().toLocaleDateString();
        case 'checkbox':
          return 'false';
        default:
          return '';
      }
    })();
    return [field.id, value];
  }))
);

export default function RecipientSigningPage() {
  const { t } = useTranslation();
  const { token = '' } = useParams();
  const [context, setContext] = useState<RecipientSigningContext | null>(null);
  const [accessCode, setAccessCode] = useState('');
  const [authenticated, setAuthenticated] = useState(false);
  const [documentUrl, setDocumentUrl] = useState<string | null>(null);
  const [fieldValues, setFieldValues] = useState<Record<string, string>>({});
  const [signerName, setSignerName] = useState('');
  const [consentAccepted, setConsentAccepted] = useState(false);
  const [declining, setDeclining] = useState(false);
  const [declineReason, setDeclineReason] = useState('');
  const [submitting, setSubmitting] = useState(false);
  const [completed, setCompleted] = useState(false);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;
    getRecipientSigningContext(token)
      .then(nextContext => {
        if (cancelled) return;
        setContext(nextContext);
        setSignerName(nextContext.recipient.name);
        setFieldValues(initialFieldValues(nextContext));
      })
      .catch(() => {
        if (!cancelled) {
          setError(t('recipientSigning.errors.load', 'This signing request is unavailable'));
        }
      });
    return () => {
      cancelled = true;
    };
  }, [t, token]);

  const loadDocument = useCallback(async (code?: string, recordView = true) => {
    setError(null);
    const blob = await downloadRecipientDocument(token, code);
    const nextUrl = URL.createObjectURL(blob);
    setDocumentUrl(current => {
      if (current) URL.revokeObjectURL(current);
      return nextUrl;
    });
    setAuthenticated(true);
    if (recordView) {
      await markRecipientViewed(token, code);
    }
  }, [token]);

  useEffect(() => {
    if (context?.recipient.authenticationMethod === 'emailLink' && !authenticated) {
      loadDocument().catch(() => {
        setError(t('recipientSigning.errors.document', 'Unable to open the document'));
      });
    }
  }, [authenticated, context, loadDocument, t]);

  useEffect(() => () => {
    if (documentUrl) URL.revokeObjectURL(documentUrl);
  }, [documentUrl]);

  const requiredFieldsComplete = useMemo(() => context?.request.fields.every(field => {
    if (!field.required) return true;
    const value = fieldValues[field.id];
    return field.type === 'checkbox' ? value === 'true' : Boolean(value?.trim());
  }) ?? false, [context, fieldValues]);

  const handleUnlock = async () => {
    setSubmitting(true);
    try {
      await loadDocument(accessCode);
    } catch {
      setError(t('recipientSigning.errors.authentication', 'The access code is incorrect'));
    } finally {
      setSubmitting(false);
    }
  };

  const handleSign = async () => {
    if (!context || !requiredFieldsComplete || !consentAccepted) return;
    setSubmitting(true);
    setError(null);
    try {
      await completeRecipientSignature(token, {
        signerName: signerName.trim() || context.recipient.name,
        signatureType: 'typed',
        accessCode: context.recipient.authenticationMethod === 'accessCode' ? accessCode : undefined,
        consentAccepted,
        consentText: t('recipientSigning.consent', 'I agree to sign this document electronically.'),
        fieldValues,
      });
      setCompleted(true);
      await loadDocument(
        context.recipient.authenticationMethod === 'accessCode' ? accessCode : undefined,
        false,
      );
    } catch {
      setError(t('recipientSigning.errors.sign', 'Unable to complete the signature'));
    } finally {
      setSubmitting(false);
    }
  };

  const handleDecline = async () => {
    if (!context) return;
    setSubmitting(true);
    try {
      await declineRecipientSignature(
        token,
        declineReason.trim(),
        context.recipient.authenticationMethod === 'accessCode' ? accessCode : undefined,
      );
      setCompleted(true);
      setDeclining(false);
    } catch {
      setError(t('recipientSigning.errors.decline', 'Unable to decline the request'));
    } finally {
      setSubmitting(false);
    }
  };

  if (!context && !error) {
    return (
      <div className={styles.page}>
        <Group justify="center" h="100dvh"><Loader /></Group>
      </div>
    );
  }

  if (!context) {
    return (
      <div className={styles.page}>
        <Alert className={styles.gate} color="red">{error}</Alert>
      </div>
    );
  }

  if (context.recipient.authenticationMethod === 'accessCode' && !authenticated) {
    return (
      <div className={styles.page}>
        <Paper className={styles.gate} withBorder p="xl" radius="sm">
          <Stack>
            <Title order={1} size="h2">{context.request.title}</Title>
            <PasswordInput
              label={t('recipientSigning.accessCode', 'Access code')}
              value={accessCode}
              onChange={event => setAccessCode(event.currentTarget.value)}
              autoComplete="one-time-code"
              autoFocus
            />
            {error && <Alert color="red">{error}</Alert>}
            <Button
              leftSection={<LockOpenRoundedIcon fontSize="small" />}
              loading={submitting}
              disabled={accessCode.length < 6}
              onClick={handleUnlock}
            >
              {t('recipientSigning.open', 'Open document')}
            </Button>
          </Stack>
        </Paper>
      </div>
    );
  }

  return (
    <div className={styles.page}>
      <header className={styles.header}>
        <div>
          <Title order={1} size="h3">{context.request.title}</Title>
          <Text size="sm" c="dimmed">{context.recipient.name}</Text>
        </div>
        {documentUrl && (
          <Button
            component="a"
            href={documentUrl}
            download={context.request.originalFilename}
            variant="subtle"
            leftSection={<DownloadRoundedIcon fontSize="small" />}
          >
            {t('recipientSigning.download', 'Download')}
          </Button>
        )}
      </header>
      <main className={styles.main}>
        <section className={styles.document} aria-label={t('recipientSigning.document', 'Document')}>
          {documentUrl && (
            <iframe
              className={styles.documentFrame}
              src={documentUrl}
              title={t('recipientSigning.documentPreview', 'Document preview')}
            />
          )}
        </section>
        <section className={styles.form} aria-label={t('recipientSigning.fields', 'Signing fields')}>
          <Stack gap="md">
            {completed ? (
              <Alert color="green" icon={<CheckCircleRoundedIcon />} title={t('recipientSigning.completed', 'Completed')}>
                {t('recipientSigning.completedBody', 'Your response has been recorded.')}
              </Alert>
            ) : (
              <>
                {context.request.message && <Text size="sm">{context.request.message}</Text>}
                <TextInput
                  label={t('recipientSigning.signerName', 'Signer name')}
                  value={signerName}
                  onChange={event => setSignerName(event.currentTarget.value)}
                  required
                />
                {context.request.fields.map(field => (
                  <RecipientField
                    key={field.id}
                    field={field}
                    value={fieldValues[field.id] ?? ''}
                    onChange={value => setFieldValues(current => ({ ...current, [field.id]: value }))}
                  />
                ))}
                <Checkbox
                  checked={consentAccepted}
                  onChange={event => setConsentAccepted(event.currentTarget.checked)}
                  label={t('recipientSigning.consent', 'I agree to sign this document electronically.')}
                  required
                />
                {error && <Alert color="red">{error}</Alert>}
                {declining ? (
                  <Stack gap="xs">
                    <Textarea
                      label={t('recipientSigning.declineReason', 'Reason')}
                      value={declineReason}
                      onChange={event => setDeclineReason(event.currentTarget.value)}
                      minRows={3}
                    />
                    <Group grow>
                      <Button variant="default" onClick={() => setDeclining(false)}>
                        {t('cancel', 'Cancel')}
                      </Button>
                      <Button color="red" loading={submitting} onClick={handleDecline}>
                        {t('recipientSigning.confirmDecline', 'Decline')}
                      </Button>
                    </Group>
                  </Stack>
                ) : (
                  <Group grow>
                    <Button variant="default" color="red" onClick={() => setDeclining(true)}>
                      {t('recipientSigning.decline', 'Decline')}
                    </Button>
                    <Button
                      leftSection={<SendRoundedIcon fontSize="small" />}
                      loading={submitting}
                      disabled={!signerName.trim() || !requiredFieldsComplete || !consentAccepted}
                      onClick={handleSign}
                    >
                      {t('recipientSigning.sign', 'Sign')}
                    </Button>
                  </Group>
                )}
              </>
            )}
          </Stack>
        </section>
      </main>
    </div>
  );
}

function RecipientField({
  field,
  value,
  onChange,
}: {
  field: SigningField;
  value: string;
  onChange: (value: string) => void;
}) {
  if (field.type === 'checkbox') {
    return (
      <Checkbox
        checked={value === 'true'}
        onChange={event => onChange(String(event.currentTarget.checked))}
        label={field.label}
        required={field.required}
      />
    );
  }
  return (
    <TextInput
      label={field.label}
      value={value}
      onChange={event => onChange(event.currentTarget.value)}
      required={field.required}
      readOnly={field.readOnly || ['name', 'email', 'dateSigned'].includes(field.type)}
    />
  );
}
