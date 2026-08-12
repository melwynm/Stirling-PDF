import { useCallback, useEffect, useState } from 'react';
import { ActionIcon, Alert, Badge, Group, Loader, ScrollArea, Stack, Table, Text } from '@mantine/core';
import DeleteOutlineRoundedIcon from '@mui/icons-material/DeleteOutlineRounded';
import NoteAddOutlinedIcon from '@mui/icons-material/NoteAddOutlined';
import RefreshRoundedIcon from '@mui/icons-material/RefreshRounded';
import { useTranslation } from 'react-i18next';
import { Tooltip } from '@app/components/shared/Tooltip';
import {
  deleteSignatureTemplate,
  instantiateSignatureTemplate,
  listSignatureTemplates,
  type SignatureTemplateView,
} from '@app/services/signingWorkflowService';

export function SigningTemplateDashboard() {
  const { t } = useTranslation();
  const [templates, setTemplates] = useState<SignatureTemplateView[]>([]);
  const [loading, setLoading] = useState(true);
  const [busyId, setBusyId] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);

  const refresh = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      setTemplates(await listSignatureTemplates());
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : String(cause));
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => { void refresh(); }, [refresh]);

  const run = async (id: string, action: () => Promise<unknown>) => {
    setBusyId(id);
    setError(null);
    try {
      await action();
      await refresh();
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : String(cause));
    } finally {
      setBusyId(null);
    }
  };

  return (
    <Stack gap="sm">
      <Group justify="flex-end">
        <Tooltip content={t('requestSignatures.templates.refresh', 'Refresh')}>
          <ActionIcon variant="default" onClick={() => void refresh()} aria-label={t('requestSignatures.templates.refresh', 'Refresh')}>
            <RefreshRoundedIcon fontSize="small" />
          </ActionIcon>
        </Tooltip>
      </Group>
      {error && <Alert color="red">{error}</Alert>}
      {loading ? <Group justify="center" mih="10rem"><Loader /></Group> : (
        <ScrollArea type="auto">
          <Table striped highlightOnHover miw={600}>
            <Table.Thead><Table.Tr>
              <Table.Th>{t('requestSignatures.templates.template', 'Template')}</Table.Th>
              <Table.Th>{t('requestSignatures.templates.recipients', 'Recipients')}</Table.Th>
              <Table.Th>{t('requestSignatures.templates.updated', 'Updated')}</Table.Th>
              <Table.Th ta="right">{t('requestSignatures.templates.actions', 'Actions')}</Table.Th>
            </Table.Tr></Table.Thead>
            <Table.Tbody>
              {templates.map(template => <Table.Tr key={template.id}>
                <Table.Td><Text size="sm" fw={600}>{template.name}</Text><Text size="xs" c="dimmed">{template.originalFilename}</Text></Table.Td>
                <Table.Td><Badge variant="light">{template.defaults.recipients.length}</Badge></Table.Td>
                <Table.Td>{new Date(template.updatedAt).toLocaleString()}</Table.Td>
                <Table.Td><Group justify="flex-end" gap={4} wrap="nowrap">
                  <Tooltip content={t('requestSignatures.templates.createDraft', 'Create draft')}>
                    <ActionIcon loading={busyId === template.id} variant="subtle" onClick={() => void run(template.id, () => instantiateSignatureTemplate(template.id))} aria-label={t('requestSignatures.templates.createDraft', 'Create draft')}><NoteAddOutlinedIcon fontSize="small" /></ActionIcon>
                  </Tooltip>
                  <Tooltip content={t('requestSignatures.templates.delete', 'Delete template')}>
                    <ActionIcon loading={busyId === template.id} color="red" variant="subtle" onClick={() => {
                      if (window.confirm(t('requestSignatures.templates.confirmDelete', 'Delete this template?'))) void run(template.id, () => deleteSignatureTemplate(template.id));
                    }} aria-label={t('requestSignatures.templates.delete', 'Delete template')}><DeleteOutlineRoundedIcon fontSize="small" /></ActionIcon>
                  </Tooltip>
                </Group></Table.Td>
              </Table.Tr>)}
              {templates.length === 0 && <Table.Tr><Table.Td colSpan={4}><Text ta="center" c="dimmed" py="xl">{t('requestSignatures.templates.empty', 'No templates found')}</Text></Table.Td></Table.Tr>}
            </Table.Tbody>
          </Table>
        </ScrollArea>
      )}
    </Stack>
  );
}
