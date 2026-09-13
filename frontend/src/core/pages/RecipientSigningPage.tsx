import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { useParams } from 'react-router-dom';
import {
  Alert,
  Button,
  Checkbox,
  Group,
  Loader,
  Paper,
  PasswordInput,
  Progress,
  Radio,
  Select,
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
  requestSigningOtp,
  type RecipientSignInput,
  type SigningOtpChallenge,
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
  const { token = '' } = useParams();
  return <RecipientSigningSession key={token} token={token} />;
}

function RecipientSigningSession({ token }: { token: string }) {
  const { t } = useTranslation();
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
  const [outcome, setOutcome] = useState<'signed' | 'declined' | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [documentError, setDocumentError] = useState<string | null>(null);
  const [otp, setOtp] = useState('');
  const [otpNow, setOtpNow] = useState(Date.now);
  const [otpChallenge, setOtpChallenge] = useState<(SigningOtpChallenge & { input: RecipientSignInput }) | null>(null);
  const documentUrlRef = useRef<string | null>(null);
  const documentLoadGeneration = useRef(0);

  useEffect(() => {
    if (!otpChallenge) return;
    setOtpNow(Date.now());
    const interval = window.setInterval(() => setOtpNow(Date.now()), 1000);
    return () => window.clearInterval(interval);
  }, [otpChallenge]);

  const otpExpired = Boolean(otpChallenge && otpNow >= Date.parse(otpChallenge.expiresAt));
  const resendSeconds = otpChallenge ? Math.max(0, Math.ceil((Date.parse(otpChallenge.resendAt) - otpNow) / 1000)) : 0;

  useEffect(() => () => {
    documentLoadGeneration.current += 1;
    if (documentUrlRef.current) URL.revokeObjectURL(documentUrlRef.current);
  }, []);

  useEffect(() => {
    let cancelled = false;
    getRecipientSigningContext(token)
      .then(nextContext => {
        if (cancelled) return;
        setContext(nextContext);
        setSignerName(nextContext.recipient.name);
        setFieldValues(initialFieldValues(nextContext));
        setOutcome(nextContext.recipient.status === 'SIGNED' ? 'signed'
          : nextContext.recipient.status === 'DECLINED' ? 'declined' : null);
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
    const generation = ++documentLoadGeneration.current;
    setDocumentError(null);
    const blob = await downloadRecipientDocument(token, code);
    if (generation !== documentLoadGeneration.current) return;
    const nextUrl = URL.createObjectURL(blob);
    if (documentUrlRef.current) URL.revokeObjectURL(documentUrlRef.current);
    documentUrlRef.current = nextUrl;
    setDocumentUrl(nextUrl);
    setAuthenticated(true);
    if (recordView) {
      try {
        await markRecipientViewed(token, code);
      } catch {
        // Viewing telemetry must not turn a successful document download into an auth failure.
      }
    }
  }, [token]);

  useEffect(() => {
    if (context && context.recipient.authenticationMethod !== 'accessCode' && !authenticated) {
      loadDocument(undefined, context.recipient.status !== 'SIGNED'
        && context.recipient.status !== 'DECLINED' && context.recipient.role !== 'cc').catch(() => {
        setDocumentError(t('recipientSigning.errors.document', 'Unable to open the document'));
      });
    }
  }, [authenticated, context, loadDocument, t]);

  const incompleteFields = useMemo(() => context?.request.fields.filter(field => {
    if (!field.required) return false;
    const value = fieldValues[field.id];
    if (field.type === 'checkbox') return value !== 'true';
    if (field.type === 'radio' || field.type === 'dropdown') return !field.options.includes(value);
    return !value?.trim();
  }) ?? [], [context, fieldValues]);
  const requiredFieldsComplete = Boolean(context) && incompleteFields.length === 0;
  const requiredCount = context?.request.fields.filter(field => field.required).length ?? 0;
  const closed = Boolean(context && ['CANCELLED', 'EXPIRED', 'ARCHIVED', 'DECLINED'].includes(context.request.status));
  const canRespond = authenticated && !outcome && !closed && context?.recipient.role !== 'cc';

  const handleUnlock = async () => {
    setSubmitting(true);
    try {
      await loadDocument(accessCode, !outcome && context?.recipient.role !== 'cc');
    } catch {
      setError(t('recipientSigning.errors.authentication', 'The access code is incorrect'));
    } finally {
      setSubmitting(false);
    }
  };

  const handleSign = async () => {
    if (!context || !canRespond || submitting || !requiredFieldsComplete || !consentAccepted || otpExpired) return;
    setSubmitting(true);
    setError(null);
    try {
      const input: RecipientSignInput = otpChallenge?.input ?? {
        signerName: signerName.trim() || context.recipient.name,
        signatureType: 'typed',
        accessCode: context.recipient.authenticationMethod === 'accessCode' ? accessCode : undefined,
        consentAccepted,
        consentText: t('recipientSigning.consent', 'I agree to sign this document electronically.'),
        fieldValues,
      };
      if (context.recipient.authenticationMethod === 'emailOtp' && !otpChallenge) {
        const challenge = await requestSigningOtp(token, input);
        setOtpChallenge({ ...challenge, input });
        setOtp('');
        return;
      }
      const signedRequest = await completeRecipientSignature(token, { ...input, otp: otpChallenge ? otp : undefined });
      setContext(current => current ? { ...current, request: signedRequest } : current);
      setOutcome('signed');
    } catch {
      setOtp('');
      setError(t('recipientSigning.errors.sign', 'Unable to complete the signature'));
    } finally {
      setSubmitting(false);
    }
  };

  const handleResendOtp = async () => {
    if (!otpChallenge || submitting || resendSeconds > 0) return;
    setSubmitting(true);
    setError(null);
    try {
      const challenge = await requestSigningOtp(token, otpChallenge.input);
      setOtpChallenge({ ...challenge, input: otpChallenge.input });
      setOtp('');
    } catch {
      setError(t('recipientSigning.errors.otp', 'Unable to send a code. Wait a minute before trying again.'));
    } finally {
      setSubmitting(false);
    }
  };

  useEffect(() => {
    if (outcome !== 'signed' || !authenticated) return;
    if (documentUrlRef.current) URL.revokeObjectURL(documentUrlRef.current);
    documentUrlRef.current = null;
    setDocumentUrl(null);
    void loadDocument(context?.recipient.authenticationMethod === 'accessCode' ? accessCode : undefined, false)
      .catch(() => setDocumentError(t('recipientSigning.errors.refresh', 'Your signature was recorded, but the updated PDF could not be downloaded.')));
  }, [outcome, authenticated, accessCode, context?.recipient.authenticationMethod, loadDocument, t]);

  const handleDecline = async () => {
    if (!context || !canRespond || submitting) return;
    setSubmitting(true);
    setError(null);
    try {
      await declineRecipientSignature(
        token,
        declineReason.trim(),
        context.recipient.authenticationMethod === 'accessCode' ? accessCode : undefined,
      );
      setOutcome('declined');
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
            {documentError && (
              <Alert color="yellow" role="alert">
                {documentError}
                <Button variant="subtle" onClick={() => void loadDocument(
                  context.recipient.authenticationMethod === 'accessCode' ? accessCode : undefined, false,
                ).catch(() => setDocumentError(t('recipientSigning.errors.document', 'Unable to open the document')))}>
                  {t('recipientSigning.retryDownload', 'Retry download')}
                </Button>
              </Alert>
            )}
            {outcome === 'signed' ? (
              <Alert color="green" role="status" icon={<CheckCircleRoundedIcon />} title={t('recipientSigning.signed', 'Signature recorded')}>
                {context.request.status === 'COMPLETED'
                  ? t('recipientSigning.allCompleted', 'All required recipients have completed this request.')
                  : t('recipientSigning.waitingForOthers', 'Your signing step is complete. Other recipients still need to respond.')}
              </Alert>
            ) : outcome === 'declined' ? (
              <Alert color="yellow" role="status" title={t('recipientSigning.declined', 'Request declined')}>
                {t('recipientSigning.declinedBody', 'Your decision has been recorded. You have not signed this document.')}
              </Alert>
            ) : closed ? (
              <Alert color="yellow" role="status">{t('recipientSigning.closed', 'This request is no longer accepting responses.')}</Alert>
            ) : context.recipient.role === 'cc' ? (
              <Text>{t('recipientSigning.copyRecipient', 'You received a copy of this document. No signature is required.')}</Text>
            ) : (
              <>
                {context.request.message && <Text size="sm">{context.request.message}</Text>}
                <Text size="sm" role="status">
                  {t('recipientSigning.progress', '{{completed}} of {{total}} required fields complete', {
                    completed: requiredCount - incompleteFields.length, total: requiredCount,
                  })}
                </Text>
                <Progress value={requiredCount ? (requiredCount - incompleteFields.length) / requiredCount * 100 : 100}
                  aria-label={t('recipientSigning.fields', 'Signing fields')} />
                {incompleteFields.length > 0 && (
                  <Button variant="light" onClick={() => document.getElementById(`recipient-field-${incompleteFields[0].id}`)?.focus()}>
                    {t('recipientSigning.nextField', 'Next required field')}
                  </Button>
                )}
                <TextInput
                  label={t('recipientSigning.signerName', 'Signer name')}
                  disabled={Boolean(otpChallenge) || submitting}
                  value={signerName}
                  onChange={event => setSignerName(event.currentTarget.value)}
                  required
                />
                {context.request.fields.map(field => (
                  <RecipientField
                    key={field.id}
                    field={{ ...field, readOnly: field.readOnly || Boolean(otpChallenge) || submitting }}
                    value={fieldValues[field.id] ?? ''}
                    onChange={value => setFieldValues(current => ({ ...current, [field.id]: value }))}
                  />
                ))}
                <Checkbox
                  disabled={Boolean(otpChallenge) || submitting}
                  checked={consentAccepted}
                  onChange={event => setConsentAccepted(event.currentTarget.checked)}
                  label={t('recipientSigning.consent', 'I agree to sign this document electronically.')}
                  required
                />
                {otpChallenge && (
                  <Stack gap="xs">
                    <Text size="sm" role="status">{t('recipientSigning.otpSent', 'A verification code was sent to your email. It expires in 5 minutes.')}</Text>
                    {otpExpired && <Alert color="yellow">{t('recipientSigning.otpExpired', 'This code has expired. Request a new code to continue.')}</Alert>}
                    <TextInput label={t('recipientSigning.otp', 'Verification code')} value={otp}
                      onChange={event => setOtp(event.currentTarget.value.replace(/\D/g, '').slice(0, 6))}
                      inputMode="numeric" autoComplete="one-time-code" maxLength={6} autoFocus disabled={submitting} />
                    <Group>
                      <Button variant="subtle" disabled={submitting || resendSeconds > 0} onClick={() => void handleResendOtp()}>
                        {t('recipientSigning.resendOtp', 'Resend code')}
                      </Button>
                      <Button variant="subtle" disabled={submitting} onClick={() => { setOtpChallenge(null); setOtp(''); }}>
                        {t('recipientSigning.backToReview', 'Back to review')}
                      </Button>
                    </Group>
                    {resendSeconds > 0 && <Text size="xs">{t('recipientSigning.resendWait', 'Resend available in {{seconds}} seconds', { seconds: resendSeconds })}</Text>}
                  </Stack>
                )}
                {error && <Alert color="red" role="alert">{error}</Alert>}
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
                      <Button color="red" loading={submitting} disabled={!canRespond} onClick={handleDecline}>
                        {t('recipientSigning.confirmDecline', 'Decline')}
                      </Button>
                    </Group>
                  </Stack>
                ) : (
                  <Group grow>
                    <Button variant="default" color="red" disabled={!canRespond || submitting} onClick={() => setDeclining(true)}>
                      {t('recipientSigning.decline', 'Decline')}
                    </Button>
                    <Button
                      leftSection={<SendRoundedIcon fontSize="small" />}
                      loading={submitting}
                      disabled={!canRespond || !signerName.trim() || !requiredFieldsComplete || !consentAccepted || otpExpired || Boolean(otpChallenge && otp.length !== 6)}
                      onClick={handleSign}
                    >
                      {context.recipient.authenticationMethod === 'emailOtp'
                        ? otpChallenge ? t('recipientSigning.verifyAndSign', 'Verify and sign')
                          : t('recipientSigning.sendOtp', 'Send verification code')
                        : t('recipientSigning.sign', 'Sign')}
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
  const id = `recipient-field-${field.id}`;
  if (field.type === 'dropdown') {
    return <Select id={id} label={field.label} data={field.options} value={value || null}
      onChange={next => onChange(next ?? '')} required={field.required} readOnly={field.readOnly} />;
  }
  if (field.type === 'radio') {
    return (
      <Radio.Group label={field.label} value={value} onChange={onChange} required={field.required}>
        <Stack gap="xs">
          {field.options.map((option, index) => <Radio key={option} id={index === 0 ? id : `${id}-${index}`}
            value={option} label={option} disabled={field.readOnly} />)}
        </Stack>
      </Radio.Group>
    );
  }
  if (field.type === 'checkbox') {
    return (
      <Checkbox
        id={id}
        checked={value === 'true'}
        onChange={event => onChange(String(event.currentTarget.checked))}
        label={field.label}
        required={field.required}
        disabled={field.readOnly}
      />
    );
  }
  return (
    <TextInput
      id={id}
      label={field.label}
      value={value}
      onChange={event => onChange(event.currentTarget.value)}
      required={field.required}
      readOnly={field.readOnly || ['name', 'email', 'dateSigned'].includes(field.type)}
    />
  );
}
