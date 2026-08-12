import { useEffect, useMemo, useState, type ReactNode } from 'react';
import { useTranslation } from 'react-i18next';
import {
  ActionIcon,
  Alert,
  Box,
  Button,
  Group,
  NumberInput,
  PasswordInput,
  SegmentedControl,
  Select,
  SimpleGrid,
  Stack,
  Switch,
  Tabs,
  Text,
  Textarea,
  TextInput,
  UnstyledButton,
} from '@mantine/core';
import AlternateEmailRoundedIcon from '@mui/icons-material/AlternateEmailRounded';
import CalendarTodayRoundedIcon from '@mui/icons-material/CalendarTodayRounded';
import CheckBoxOutlineBlankRoundedIcon from '@mui/icons-material/CheckBoxOutlineBlankRounded';
import DeleteOutlineRoundedIcon from '@mui/icons-material/DeleteOutlineRounded';
import DeleteSweepRoundedIcon from '@mui/icons-material/DeleteSweepRounded';
import DrawRoundedIcon from '@mui/icons-material/DrawRounded';
import PersonAddAltRoundedIcon from '@mui/icons-material/PersonAddAltRounded';
import PersonOutlineRoundedIcon from '@mui/icons-material/PersonOutlineRounded';
import SaveOutlinedIcon from '@mui/icons-material/SaveOutlined';
import ShortTextRoundedIcon from '@mui/icons-material/ShortTextRounded';
import { createToolSteps, ToolStepProvider } from '@app/components/tools/shared/ToolStep';
import { Tooltip } from '@app/components/shared/Tooltip';
import { SigningWorkflowDashboard } from '@app/components/signing/SigningWorkflowDashboard';
import { useFileSelection } from '@app/contexts/FileContext';
import {
  SIGNING_FIELD_DRAG_TYPE,
  useSigningFieldAuthoring,
} from '@app/contexts/SigningFieldAuthoringContext';
import { useEndpointEnabled } from '@app/hooks/useEndpointConfig';
import { useNavigationActions } from '@app/contexts/NavigationContext';
import {
  createSignatureRequest,
  type SignatureRequestRecipientInput,
  type SignatureRequestView,
} from '@app/services/signingWorkflowService';
import type { BaseToolProps } from '@app/types/tool';
import { validateSigningModel, type SigningFieldType } from '@app/types/signing';

const createRecipientId = (): string => {
  if (globalThis.crypto?.randomUUID) {
    return globalThis.crypto.randomUUID();
  }
  return `recipient-${Date.now()}-${Math.random().toString(16).slice(2)}`;
};

const newRecipient = (signingOrder: number): SignatureRequestRecipientInput => ({
  id: createRecipientId(),
  name: '',
  email: '',
  phoneNumber: '',
  deliveryChannel: 'email',
  role: 'signer',
  signingOrder,
  authenticationMethod: 'emailLink',
});

const RequestSignatures = ({ onError }: BaseToolProps) => {
  const { t } = useTranslation();
  const { selectedFiles } = useFileSelection();
  const { enabled: endpointEnabled } = useEndpointEnabled('e-sign');
  const { actions: navigationActions } = useNavigationActions();
  const {
    fields,
    selectedRecipientId,
    placementType,
    setActive: setAuthoringActive,
    setRecipients: setAuthoringRecipients,
    setSelectedRecipientId,
    setPlacementType,
    clearFields,
    markSaved,
  } = useSigningFieldAuthoring();
  const steps = createToolSteps();
  const selectedFile = selectedFiles[0];

  const [title, setTitle] = useState('');
  const [message, setMessage] = useState('');
  const [requesterName, setRequesterName] = useState('');
  const [requesterEmail, setRequesterEmail] = useState('');
  const [recipients, setRecipients] = useState<SignatureRequestRecipientInput[]>([
    newRecipient(1),
  ]);
  const [sequential, setSequential] = useState(false);
  const [expiryEnabled, setExpiryEnabled] = useState(true);
  const [expiryDays, setExpiryDays] = useState(7);
  const [remindersEnabled, setRemindersEnabled] = useState(true);
  const [reminderIntervalHours, setReminderIntervalHours] = useState(48);
  const [submitting, setSubmitting] = useState(false);
  const [createdRequest, setCreatedRequest] = useState<SignatureRequestView | null>(null);
  const [submitError, setSubmitError] = useState<string | null>(null);
  const [view, setView] = useState<string | null>('create');

  useEffect(() => {
    if (!selectedFile) return;
    setTitle(current => current.trim() || selectedFile.name.replace(/\.pdf$/i, ''));
  }, [selectedFile?.fileId]);

  useEffect(() => {
    setAuthoringActive(view === 'create');
    return () => setAuthoringActive(false);
  }, [setAuthoringActive, view]);

  useEffect(() => {
    setAuthoringRecipients(recipients.map(({ accessCode: _accessCode, ...recipient }) => recipient));
  }, [recipients, setAuthoringRecipients]);

  useEffect(() => {
    if (selectedFile) {
      navigationActions.setWorkbench('viewer');
    }
  }, [navigationActions, selectedFile?.fileId]);

  const updateRecipient = (
    index: number,
    updates: Partial<SignatureRequestRecipientInput>,
  ) => {
    setRecipients(current => current.map((recipient, recipientIndex) => (
      recipientIndex === index ? { ...recipient, ...updates } : recipient
    )));
    setCreatedRequest(null);
  };

  const addRecipient = () => {
    setRecipients(current => [...current, newRecipient(current.length + 1)]);
    setCreatedRequest(null);
  };

  const removeRecipient = (index: number) => {
    setRecipients(current => current
      .filter((_, recipientIndex) => recipientIndex !== index)
      .map((recipient, recipientIndex) => ({
        ...recipient,
        signingOrder: sequential ? recipientIndex + 1 : recipient.signingOrder,
      })));
    setCreatedRequest(null);
  };

  const validationIssues = useMemo(() => {
    const modelIssues = validateSigningModel(
      recipients.map(({ accessCode: _accessCode, ...recipient }) => recipient),
      fields,
    );
    const hasInvalidEmail = recipients.some(recipient => (
      !/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(recipient.email.trim())
    ));
    const hasInvalidAccessCode = recipients.some(recipient => (
      recipient.authenticationMethod === 'accessCode'
      && (recipient.accessCode?.length ?? 0) < 6
    ));
    const hasInvalidPhone = recipients.some(recipient => (
      recipient.deliveryChannel === 'sms'
      && !/^\+[1-9][0-9]{7,14}$/.test(recipient.phoneNumber?.trim() ?? '')
    ));
    const hasInvalidRequesterEmail = Boolean(
      requesterEmail.trim()
      && !/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(requesterEmail.trim()),
    );
    return {
      modelIssues,
      hasInvalidEmail,
      hasInvalidAccessCode,
      hasInvalidPhone,
      hasInvalidRequesterEmail,
    };
  }, [fields, recipients, requesterEmail]);

  const canSubmit = Boolean(
    selectedFile
    && title.trim()
    && endpointEnabled
    && !submitting
    && validationIssues.modelIssues.length === 0
    && !validationIssues.hasInvalidEmail
    && !validationIssues.hasInvalidAccessCode
    && !validationIssues.hasInvalidPhone
    && !validationIssues.hasInvalidRequesterEmail,
  );

  const handleCreate = async () => {
    if (!selectedFile || !canSubmit) return;
    setSubmitting(true);
    setCreatedRequest(null);
    setSubmitError(null);
    try {
      const expiresAt = expiryEnabled
        ? new Date(Date.now() + expiryDays * 24 * 60 * 60 * 1000).toISOString()
        : undefined;
      const result = await createSignatureRequest(selectedFile, {
        title: title.trim(),
        message: message.trim() || undefined,
        requesterName: requesterName.trim() || undefined,
        requesterEmail: requesterEmail.trim() || undefined,
        expiresAt,
        signingOrder: sequential,
        remindersEnabled,
        reminderIntervalHours,
        recipients: recipients.map((recipient, index) => ({
          ...recipient,
          signingOrder: sequential ? recipient.signingOrder : index + 1,
          accessCode: recipient.authenticationMethod === 'accessCode'
            ? recipient.accessCode
            : undefined,
        })),
        fields,
      });
      setCreatedRequest(result);
      markSaved();
    } catch (error) {
      const messageText = error instanceof Error
        ? error.message
        : t('requestSignatures.errors.create', 'Unable to create the signature request');
      setSubmitError(messageText);
      onError?.(messageText);
    } finally {
      setSubmitting(false);
    }
  };

  const recipientContent = (
    <Stack gap="sm">
      {recipients.map((recipient, index) => (
        <Box
          key={recipient.id}
          p="sm"
          style={{
            border: '1px solid var(--mantine-color-default-border)',
            borderRadius: '6px',
          }}
        >
          <Stack gap="xs">
            <Group justify="space-between" wrap="nowrap">
              <Text size="sm" fw={600}>
                {t('requestSignatures.recipient', 'Recipient')} {index + 1}
              </Text>
              <Tooltip content={t('requestSignatures.removeRecipient', 'Remove recipient')}>
                <ActionIcon
                  variant="subtle"
                  color="red"
                  size="sm"
                  disabled={recipients.length === 1}
                  onClick={() => removeRecipient(index)}
                  aria-label={t('requestSignatures.removeRecipient', 'Remove recipient')}
                >
                  <DeleteOutlineRoundedIcon fontSize="small" />
                </ActionIcon>
              </Tooltip>
            </Group>
            <TextInput
              label={t('requestSignatures.name', 'Name')}
              value={recipient.name}
              onChange={event => updateRecipient(index, { name: event.currentTarget.value })}
              required
            />
            <TextInput
              type="email"
              label={t('requestSignatures.email', 'Email')}
              value={recipient.email}
              onChange={event => updateRecipient(index, { email: event.currentTarget.value })}
              error={recipient.email && !/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(recipient.email)
                ? t('requestSignatures.errors.email', 'Enter a valid email address')
                : undefined}
              required
            />
            <Select
              label={t('requestSignatures.deliveryChannel', 'Delivery')}
              value={recipient.deliveryChannel}
              data={[
                { value: 'email', label: t('requestSignatures.deliveryChannels.email', 'Email') },
                { value: 'sms', label: t('requestSignatures.deliveryChannels.sms', 'SMS') },
              ]}
              onChange={value => updateRecipient(index, {
                deliveryChannel: (value ?? 'email') as SignatureRequestRecipientInput['deliveryChannel'],
              })}
              allowDeselect={false}
            />
            {recipient.deliveryChannel === 'sms' && (
              <TextInput
                type="tel"
                label={t('requestSignatures.phoneNumber', 'Phone number')}
                placeholder="+23051234567"
                value={recipient.phoneNumber ?? ''}
                onChange={event => updateRecipient(index, { phoneNumber: event.currentTarget.value })}
                error={recipient.phoneNumber && !/^\+[1-9][0-9]{7,14}$/.test(recipient.phoneNumber.trim())
                  ? t('requestSignatures.errors.phone', 'Enter a phone number in E.164 format')
                  : undefined}
                required
              />
            )}
            <Select
              label={t('requestSignatures.role', 'Role')}
              value={recipient.role}
              data={[
                { value: 'signer', label: t('requestSignatures.roles.signer', 'Signer') },
                { value: 'approver', label: t('requestSignatures.roles.approver', 'Approver') },
                { value: 'cc', label: t('requestSignatures.roles.cc', 'Receives a copy') },
              ]}
              onChange={value => updateRecipient(index, {
                role: (value ?? 'signer') as SignatureRequestRecipientInput['role'],
              })}
              allowDeselect={false}
            />
            {sequential && (
              <NumberInput
                label={t('requestSignatures.order', 'Routing order')}
                min={1}
                step={1}
                value={recipient.signingOrder}
                onChange={value => updateRecipient(index, {
                  signingOrder: typeof value === 'number' ? value : index + 1,
                })}
              />
            )}
            <Select
              label={t('requestSignatures.authentication', 'Authentication')}
              value={recipient.authenticationMethod}
              data={[
                {
                  value: 'emailLink',
                  label: t('requestSignatures.authenticationMethods.emailLink', 'Email link'),
                },
                {
                  value: 'accessCode',
                  label: t('requestSignatures.authenticationMethods.accessCode', 'Access code'),
                },
              ]}
              onChange={value => updateRecipient(index, {
                authenticationMethod: (value ?? 'emailLink') as SignatureRequestRecipientInput['authenticationMethod'],
                accessCode: value === 'accessCode' ? recipient.accessCode : undefined,
              })}
              allowDeselect={false}
            />
            {recipient.authenticationMethod === 'accessCode' && (
              <PasswordInput
                label={t('requestSignatures.accessCode', 'Access code')}
                value={recipient.accessCode ?? ''}
                onChange={event => updateRecipient(index, { accessCode: event.currentTarget.value })}
                error={(recipient.accessCode?.length ?? 0) > 0 && (recipient.accessCode?.length ?? 0) < 6
                  ? t('requestSignatures.errors.accessCode', 'Use at least 6 characters')
                  : undefined}
                required
              />
            )}
          </Stack>
        </Box>
      ))}
      <Button
        variant="light"
        leftSection={<PersonAddAltRoundedIcon fontSize="small" />}
        onClick={addRecipient}
      >
        {t('requestSignatures.addRecipient', 'Add recipient')}
      </Button>
    </Stack>
  );

  const fieldPalette: Array<{
    type: SigningFieldType;
    label: string;
    icon: ReactNode;
  }> = [
    {
      type: 'signature',
      label: t('requestSignatures.fieldTypes.signature', 'Signature'),
      icon: <DrawRoundedIcon fontSize="small" />,
    },
    {
      type: 'initials',
      label: t('requestSignatures.fieldTypes.initials', 'Initials'),
      icon: <DrawRoundedIcon fontSize="small" />,
    },
    {
      type: 'name',
      label: t('requestSignatures.fieldTypes.name', 'Name'),
      icon: <PersonOutlineRoundedIcon fontSize="small" />,
    },
    {
      type: 'email',
      label: t('requestSignatures.fieldTypes.email', 'Email'),
      icon: <AlternateEmailRoundedIcon fontSize="small" />,
    },
    {
      type: 'dateSigned',
      label: t('requestSignatures.fieldTypes.dateSigned', 'Date signed'),
      icon: <CalendarTodayRoundedIcon fontSize="small" />,
    },
    {
      type: 'text',
      label: t('requestSignatures.fieldTypes.text', 'Text'),
      icon: <ShortTextRoundedIcon fontSize="small" />,
    },
    {
      type: 'checkbox',
      label: t('requestSignatures.fieldTypes.checkbox', 'Checkbox'),
      icon: <CheckBoxOutlineBlankRoundedIcon fontSize="small" />,
    },
  ];
  const assignableRecipients = recipients.filter(recipient => recipient.role !== 'cc');
  const fieldAuthoringContent = (
    <Stack gap="sm">
      <Select
        label={t('requestSignatures.fieldRecipient', 'Recipient')}
        value={selectedRecipientId}
        data={assignableRecipients.map(recipient => ({
          value: recipient.id,
          label: recipient.name || recipient.email || t('requestSignatures.unnamedRecipient', 'Unnamed recipient'),
        }))}
        onChange={setSelectedRecipientId}
        allowDeselect={false}
        disabled={assignableRecipients.length === 0}
      />
      <SimpleGrid cols={2} spacing="xs">
        {fieldPalette.map(item => {
          const selected = placementType === item.type;
          const disabled = !selectedFile || !selectedRecipientId;
          return (
            <UnstyledButton
              key={item.type}
              draggable={!disabled}
              disabled={disabled}
              aria-pressed={selected}
              onClick={() => setPlacementType(selected ? null : item.type)}
              onDragStart={event => {
                if (!selectedRecipientId) return;
                event.dataTransfer.effectAllowed = 'copy';
                event.dataTransfer.setData(SIGNING_FIELD_DRAG_TYPE, JSON.stringify({
                  type: item.type,
                  recipientId: selectedRecipientId,
                }));
                setPlacementType(item.type);
              }}
              style={{
                minHeight: '3.25rem',
                display: 'flex',
                flexDirection: 'column',
                alignItems: 'center',
                justifyContent: 'center',
                gap: '0.25rem',
                padding: '0.4rem',
                border: `1px solid ${selected
                  ? 'var(--mantine-primary-color-filled)'
                  : 'var(--mantine-color-default-border)'}`,
                borderRadius: '6px',
                color: selected
                  ? 'var(--mantine-primary-color-filled)'
                  : 'var(--mantine-color-text)',
                opacity: disabled ? 0.45 : 1,
                background: selected
                  ? 'var(--mantine-primary-color-light)'
                  : 'var(--mantine-color-body)',
              }}
            >
              {item.icon}
              <Text size="xs" ta="center">{item.label}</Text>
            </UnstyledButton>
          );
        })}
      </SimpleGrid>
      <Group justify="space-between" wrap="nowrap">
        <Text size="xs" c="dimmed">
          {t('requestSignatures.fieldCount', '{{count}} fields', { count: fields.length })}
        </Text>
        <Button
          variant="subtle"
          color="red"
          size="compact-xs"
          leftSection={<DeleteSweepRoundedIcon fontSize="small" />}
          disabled={fields.length === 0}
          onClick={clearFields}
        >
          {t('requestSignatures.clearFields', 'Clear')}
        </Button>
      </Group>
    </Stack>
  );

  const routingContent = (
    <Stack gap="sm">
      <SegmentedControl
        fullWidth
        value={sequential ? 'sequential' : 'parallel'}
        onChange={value => setSequential(value === 'sequential')}
        data={[
          { value: 'parallel', label: t('requestSignatures.routing.parallel', 'Parallel') },
          { value: 'sequential', label: t('requestSignatures.routing.sequential', 'Sequential') },
        ]}
        aria-label={t('requestSignatures.routing.label', 'Routing')}
      />
      <Switch
        label={t('requestSignatures.expiry', 'Set expiry')}
        checked={expiryEnabled}
        onChange={event => setExpiryEnabled(event.currentTarget.checked)}
      />
      {expiryEnabled && (
        <NumberInput
          label={t('requestSignatures.expiryDays', 'Expires after days')}
          min={1}
          max={365}
          value={expiryDays}
          onChange={value => setExpiryDays(typeof value === 'number' ? value : 7)}
        />
      )}
      <Switch
        label={t('requestSignatures.reminders', 'Send reminders')}
        checked={remindersEnabled}
        onChange={event => setRemindersEnabled(event.currentTarget.checked)}
      />
      {remindersEnabled && (
        <Select
          label={t('requestSignatures.reminderInterval', 'Reminder interval')}
          value={String(reminderIntervalHours)}
          data={[
            { value: '24', label: t('requestSignatures.intervals.daily', 'Every day') },
            { value: '48', label: t('requestSignatures.intervals.twoDays', 'Every 2 days') },
            { value: '72', label: t('requestSignatures.intervals.threeDays', 'Every 3 days') },
            { value: '168', label: t('requestSignatures.intervals.weekly', 'Every week') },
          ]}
          onChange={value => setReminderIntervalHours(Number(value ?? 48))}
          allowDeselect={false}
        />
      )}
    </Stack>
  );

  const detailsContent = (
    <Stack gap="sm">
      <TextInput
        label={t('requestSignatures.title', 'Request title')}
        value={title}
        onChange={event => setTitle(event.currentTarget.value)}
        required
      />
      <TextInput
        label={t('requestSignatures.requesterName', 'Sender name')}
        value={requesterName}
        onChange={event => setRequesterName(event.currentTarget.value)}
      />
      <TextInput
        type="email"
        label={t('requestSignatures.requesterEmail', 'Sender email')}
        value={requesterEmail}
        onChange={event => setRequesterEmail(event.currentTarget.value)}
        error={validationIssues.hasInvalidRequesterEmail
          ? t('requestSignatures.errors.email', 'Enter a valid email address')
          : undefined}
      />
      <Textarea
        label={t('requestSignatures.message', 'Message')}
        value={message}
        minRows={3}
        autosize
        onChange={event => setMessage(event.currentTarget.value)}
      />
    </Stack>
  );

  return (
    <Stack gap="sm" p="sm">
      <Tabs value={view} onChange={setView} keepMounted={false}>
        <Tabs.List grow>
          <Tabs.Tab value="create">{t('requestSignatures.views.create', 'Create')}</Tabs.Tab>
          <Tabs.Tab value="manage">{t('requestSignatures.views.manage', 'Manage')}</Tabs.Tab>
        </Tabs.List>
        <Tabs.Panel value="create" pt="sm">
          <Stack gap="sm">
            <ToolStepProvider forceStepNumbers>
              {steps.createFilesStep({ selectedFiles, minFiles: 1 })}
              {steps.create(t('requestSignatures.steps.recipients', 'Recipients'), {}, recipientContent)}
              {steps.create(t('requestSignatures.steps.fields', 'Fields'), {}, fieldAuthoringContent)}
              {steps.create(t('requestSignatures.steps.routing', 'Routing and timing'), {}, routingContent)}
              {steps.create(t('requestSignatures.steps.details', 'Request details'), {}, detailsContent)}
            </ToolStepProvider>

            {createdRequest && (
              <Alert color="green" title={t('requestSignatures.created', 'Draft created')}>
                <Text size="sm">{createdRequest.title}</Text>
                <Text size="xs" c="dimmed">{createdRequest.id}</Text>
              </Alert>
            )}

            {submitError && (
              <Alert color="red" title={t('requestSignatures.errors.create', 'Unable to create the signature request')}>
                <Text size="sm">{submitError}</Text>
              </Alert>
            )}

            <Button
              fullWidth
              leftSection={<SaveOutlinedIcon fontSize="small" />}
              loading={submitting}
              disabled={!canSubmit}
              onClick={handleCreate}
            >
              {t('requestSignatures.createDraft', 'Create draft')}
            </Button>
          </Stack>
        </Tabs.Panel>
        <Tabs.Panel value="manage" pt="sm">
          <SigningWorkflowDashboard />
        </Tabs.Panel>
      </Tabs>
    </Stack>
  );
};

export default RequestSignatures;
