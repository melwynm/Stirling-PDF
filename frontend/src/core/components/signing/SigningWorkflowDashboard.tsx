import { useCallback, useEffect, useMemo, useState } from 'react';
import { useTranslation } from 'react-i18next';
import {
  ActionIcon,
  Alert,
  Badge,
  Box,
  Button,
  Group,
  Loader,
  Modal,
  ScrollArea,
  Select,
  Stack,
  Table,
  Text,
  TextInput,
} from '@mantine/core';
import ArchiveOutlinedIcon from '@mui/icons-material/ArchiveOutlined';
import CancelOutlinedIcon from '@mui/icons-material/CancelOutlined';
import DeleteOutlineRoundedIcon from '@mui/icons-material/DeleteOutlineRounded';
import DownloadOutlinedIcon from '@mui/icons-material/DownloadOutlined';
import NotificationsActiveOutlinedIcon from '@mui/icons-material/NotificationsActiveOutlined';
import RefreshRoundedIcon from '@mui/icons-material/RefreshRounded';
import ReplayRoundedIcon from '@mui/icons-material/ReplayRounded';
import SearchRoundedIcon from '@mui/icons-material/SearchRounded';
import VisibilityOutlinedIcon from '@mui/icons-material/VisibilityOutlined';
import { Tooltip } from '@app/components/shared/Tooltip';
import {
  archiveSignatureRequest,
  cancelSignatureRequest,
  deleteSignatureRequest,
  getSignatureAudit,
  listSignatureRequests,
  remindSignatureRequest,
  retrySignatureWebhooks,
  signatureRequestDownloadUrl,
  type SignatureAuditEvent,
  type SignatureRequestView,
  type SigningWorkflowStatus,
} from '@app/services/signingWorkflowService';

const ACTIVE_STATUSES: SigningWorkflowStatus[] = ['DRAFT', 'SENT', 'IN_PROGRESS'];
const ARCHIVABLE_STATUSES: SigningWorkflowStatus[] = ['COMPLETED', 'CANCELLED', 'DECLINED', 'EXPIRED'];

export function SigningWorkflowDashboard() {
  const { t } = useTranslation();
  const [requests, setRequests] = useState<SignatureRequestView[]>([]);
  const [selected, setSelected] = useState<SignatureRequestView | null>(null);
  const [audit, setAudit] = useState<SignatureAuditEvent[]>([]);
  const [query, setQuery] = useState('');
  const [status, setStatus] = useState<string>('ALL');
  const [loading, setLoading] = useState(true);
  const [busyId, setBusyId] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);

  const refresh = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      setRequests(await listSignatureRequests(status === 'ALL' ? undefined : status as SigningWorkflowStatus));
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : String(cause));
    } finally {
      setLoading(false);
    }
  }, [status]);

  useEffect(() => { void refresh(); }, [refresh]);

  const filtered = useMemo(() => {
    const needle = query.trim().toLowerCase();
    if (!needle) return requests;
    return requests.filter(request => [
      request.title,
      request.originalFilename,
      request.requesterName,
      request.requesterEmail,
      ...request.recipients.flatMap(recipient => [recipient.name, recipient.email]),
    ].some(value => value?.toLowerCase().includes(needle)));
  }, [query, requests]);

  const runAction = async (requestId: string, action: () => Promise<unknown>) => {
    setBusyId(requestId);
    setError(null);
    try {
      await action();
      if (selected?.id === requestId) setSelected(null);
      await refresh();
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : String(cause));
    } finally {
      setBusyId(null);
    }
  };

  const openDetails = async (request: SignatureRequestView) => {
    setSelected(request);
    setAudit([]);
    try {
      setAudit(await getSignatureAudit(request.id));
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : String(cause));
    }
  };

  return (
    <Stack gap="sm">
      <Group align="end" wrap="wrap">
        <TextInput
          style={{ flex: '1 1 16rem' }}
          label={t('requestSignatures.dashboard.search', 'Search')}
          leftSection={<SearchRoundedIcon fontSize="small" />}
          value={query}
          onChange={event => setQuery(event.currentTarget.value)}
        />
        <Select
          w="12rem"
          label={t('requestSignatures.dashboard.status', 'Status')}
          value={status}
          onChange={value => setStatus(value ?? 'ALL')}
          allowDeselect={false}
          data={['ALL', 'DRAFT', 'SENT', 'IN_PROGRESS', 'COMPLETED', 'CANCELLED', 'DECLINED', 'EXPIRED', 'ARCHIVED']}
        />
        <Tooltip content={t('requestSignatures.dashboard.refresh', 'Refresh')}>
          <ActionIcon size="lg" variant="default" onClick={() => void refresh()} aria-label={t('requestSignatures.dashboard.refresh', 'Refresh')}>
            <RefreshRoundedIcon fontSize="small" />
          </ActionIcon>
        </Tooltip>
      </Group>

      {error && <Alert color="red">{error}</Alert>}
      {loading ? <Group justify="center" mih="12rem"><Loader /></Group> : (
        <ScrollArea type="auto">
          <Table striped highlightOnHover miw={760} verticalSpacing="sm">
            <Table.Thead>
              <Table.Tr>
                <Table.Th>{t('requestSignatures.dashboard.request', 'Request')}</Table.Th>
                <Table.Th>{t('requestSignatures.dashboard.status', 'Status')}</Table.Th>
                <Table.Th>{t('requestSignatures.dashboard.progress', 'Progress')}</Table.Th>
                <Table.Th>{t('requestSignatures.dashboard.updated', 'Updated')}</Table.Th>
                <Table.Th ta="right">{t('requestSignatures.dashboard.actions', 'Actions')}</Table.Th>
              </Table.Tr>
            </Table.Thead>
            <Table.Tbody>
              {filtered.map(request => {
                const completed = request.recipients.filter(recipient => recipient.status === 'SIGNED').length;
                const busy = busyId === request.id;
                return (
                  <Table.Tr key={request.id}>
                    <Table.Td>
                      <Text size="sm" fw={600}>{request.title}</Text>
                      <Text size="xs" c="dimmed">{request.originalFilename}</Text>
                    </Table.Td>
                    <Table.Td><Badge variant="light">{request.status.replace('_', ' ')}</Badge></Table.Td>
                    <Table.Td>{completed}/{request.recipients.length}</Table.Td>
                    <Table.Td>{new Date(request.updatedAt).toLocaleString()}</Table.Td>
                    <Table.Td>
                      <Group justify="flex-end" gap={4} wrap="nowrap">
                        <Tooltip content={t('requestSignatures.dashboard.view', 'View details')}>
                          <ActionIcon variant="subtle" onClick={() => void openDetails(request)} aria-label={t('requestSignatures.dashboard.view', 'View details')}><VisibilityOutlinedIcon fontSize="small" /></ActionIcon>
                        </Tooltip>
                        <Tooltip content={t('requestSignatures.dashboard.download', 'Download')}>
                          <ActionIcon component="a" href={signatureRequestDownloadUrl(request.id)} variant="subtle" aria-label={t('requestSignatures.dashboard.download', 'Download')}><DownloadOutlinedIcon fontSize="small" /></ActionIcon>
                        </Tooltip>
                        {(request.status === 'SENT' || request.status === 'IN_PROGRESS') && (
                          <Tooltip content={t('requestSignatures.dashboard.remind', 'Send reminder')}>
                            <ActionIcon loading={busy} variant="subtle" onClick={() => void runAction(request.id, () => remindSignatureRequest(request.id))} aria-label={t('requestSignatures.dashboard.remind', 'Send reminder')}><NotificationsActiveOutlinedIcon fontSize="small" /></ActionIcon>
                          </Tooltip>
                        )}
                        {ACTIVE_STATUSES.includes(request.status) && (
                          <Tooltip content={t('requestSignatures.dashboard.cancel', 'Cancel')}>
                            <ActionIcon loading={busy} color="red" variant="subtle" onClick={() => void runAction(request.id, () => cancelSignatureRequest(request.id))} aria-label={t('requestSignatures.dashboard.cancel', 'Cancel')}><CancelOutlinedIcon fontSize="small" /></ActionIcon>
                          </Tooltip>
                        )}
                        {ARCHIVABLE_STATUSES.includes(request.status) && (
                          <Tooltip content={t('requestSignatures.dashboard.archive', 'Archive')}>
                            <ActionIcon loading={busy} variant="subtle" onClick={() => void runAction(request.id, () => archiveSignatureRequest(request.id))} aria-label={t('requestSignatures.dashboard.archive', 'Archive')}><ArchiveOutlinedIcon fontSize="small" /></ActionIcon>
                          </Tooltip>
                        )}
                        {request.status === 'ARCHIVED' && (
                          <Tooltip content={t('requestSignatures.dashboard.delete', 'Delete permanently')}>
                            <ActionIcon loading={busy} color="red" variant="subtle" onClick={() => {
                              if (window.confirm(t('requestSignatures.dashboard.confirmDelete', 'Permanently delete this request?'))) {
                                void runAction(request.id, () => deleteSignatureRequest(request.id));
                              }
                            }} aria-label={t('requestSignatures.dashboard.delete', 'Delete permanently')}><DeleteOutlineRoundedIcon fontSize="small" /></ActionIcon>
                          </Tooltip>
                        )}
                      </Group>
                    </Table.Td>
                  </Table.Tr>
                );
              })}
              {filtered.length === 0 && <Table.Tr><Table.Td colSpan={5}><Text ta="center" c="dimmed" py="xl">{t('requestSignatures.dashboard.empty', 'No signature requests found')}</Text></Table.Td></Table.Tr>}
            </Table.Tbody>
          </Table>
        </ScrollArea>
      )}

      <Modal opened={selected !== null} onClose={() => setSelected(null)} title={selected?.title} size="lg">
        {selected && <Stack gap="md">
          <Group justify="space-between"><Badge variant="light">{selected.status.replace('_', ' ')}</Badge><Text size="xs" c="dimmed">{selected.id}</Text></Group>
          <Box><Text size="sm" fw={600}>{t('requestSignatures.dashboard.recipients', 'Recipients')}</Text>{selected.recipients.map(recipient => <Group key={recipient.id} justify="space-between" py={4}><Text size="sm">{recipient.name} · {recipient.email}</Text><Badge size="sm" variant="outline">{recipient.status ?? 'PENDING'}</Badge></Group>)}</Box>
          <Box><Text size="sm" fw={600}>{t('requestSignatures.dashboard.audit', 'Audit timeline')}</Text>{audit.map(event => <Box key={event.id} py="xs" style={{ borderBottom: '1px solid var(--mantine-color-default-border)' }}><Group justify="space-between"><Text size="sm" fw={500}>{event.type.replaceAll('_', ' ')}</Text><Text size="xs" c="dimmed">{new Date(event.timestamp).toLocaleString()}</Text></Group><Text size="xs" c="dimmed">{event.message}</Text></Box>)}</Box>
          <Group justify="flex-end">
            <Button variant="default" leftSection={<ReplayRoundedIcon fontSize="small" />} onClick={() => void runAction(selected.id, () => retrySignatureWebhooks(selected.id))}>{t('requestSignatures.dashboard.retryWebhooks', 'Retry webhooks')}</Button>
            <Button component="a" href={signatureRequestDownloadUrl(selected.id)} leftSection={<DownloadOutlinedIcon fontSize="small" />}>{t('requestSignatures.dashboard.download', 'Download')}</Button>
          </Group>
        </Stack>}
      </Modal>
    </Stack>
  );
}
