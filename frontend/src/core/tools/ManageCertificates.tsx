import { useCallback, useEffect, useState } from 'react';
import {
  ActionIcon,
  Alert,
  Badge,
  Button,
  FileInput,
  Group,
  Loader,
  Stack,
  Table,
  Text,
  Tooltip,
} from '@mantine/core';
import DeleteOutlineRoundedIcon from '@mui/icons-material/DeleteOutlineRounded';
import DownloadOutlinedIcon from '@mui/icons-material/DownloadOutlined';
import RefreshRoundedIcon from '@mui/icons-material/RefreshRounded';
import UploadFileRoundedIcon from '@mui/icons-material/UploadFileRounded';
import { useTranslation } from 'react-i18next';
import {
  deleteSigningTrustCertificate,
  importSigningTrustCertificate,
  listSigningTrustCertificates,
  signingTrustCertificateUrl,
  SigningTrustCertificate,
} from '@app/services/signingTrustService';
import { BaseToolProps } from '@app/types/tool';

const subjectName = (distinguishedName: string): string => {
  const commonName = distinguishedName.match(/(?:^|,)CN=([^,]+)/i)?.[1];
  return commonName ?? distinguishedName;
};

const ManageCertificates = (_props: BaseToolProps) => {
  const { t } = useTranslation();
  const [certificates, setCertificates] = useState<SigningTrustCertificate[]>([]);
  const [selectedFile, setSelectedFile] = useState<File | null>(null);
  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const refresh = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      setCertificates(await listSigningTrustCertificates());
    } catch (requestError) {
      setError(requestError instanceof Error ? requestError.message : String(requestError));
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    void refresh();
  }, [refresh]);

  const upload = async () => {
    if (!selectedFile) return;
    setBusy(true);
    setError(null);
    try {
      await importSigningTrustCertificate(selectedFile);
      setSelectedFile(null);
      await refresh();
    } catch (requestError) {
      setError(requestError instanceof Error ? requestError.message : String(requestError));
    } finally {
      setBusy(false);
    }
  };

  const remove = async (certificate: SigningTrustCertificate) => {
    if (!window.confirm(t('manageCertificates.confirmDelete', 'Delete this trust certificate?'))) return;
    setBusy(true);
    setError(null);
    try {
      await deleteSigningTrustCertificate(certificate.fingerprint);
      await refresh();
    } catch (requestError) {
      setError(requestError instanceof Error ? requestError.message : String(requestError));
    } finally {
      setBusy(false);
    }
  };

  return (
    <Stack gap="lg" p="md">
      <Group align="end" wrap="wrap">
        <FileInput
          label={t('manageCertificates.import', 'Import trust certificate')}
          placeholder={t('manageCertificates.chooseFile', 'Choose PEM or DER certificate')}
          accept=".cer,.crt,.pem,application/pkix-cert,application/x-x509-ca-cert"
          value={selectedFile}
          onChange={setSelectedFile}
          clearable
          style={{ flex: '1 1 280px' }}
        />
        <Button
          leftSection={<UploadFileRoundedIcon fontSize="small" />}
          onClick={() => void upload()}
          disabled={!selectedFile}
          loading={busy}
        >
          {t('manageCertificates.importAction', 'Import')}
        </Button>
        <Tooltip label={t('manageCertificates.refresh', 'Refresh')}>
          <ActionIcon
            size="lg"
            variant="default"
            onClick={() => void refresh()}
            aria-label={t('manageCertificates.refresh', 'Refresh')}
          >
            <RefreshRoundedIcon fontSize="small" />
          </ActionIcon>
        </Tooltip>
      </Group>

      {error && (
        <Alert color="red" title={t('manageCertificates.unavailable', 'Certificate management unavailable')}>
          {error}
        </Alert>
      )}

      {loading ? (
        <Group justify="center" py="xl"><Loader size="sm" /></Group>
      ) : (
        <Table.ScrollContainer minWidth={760}>
          <Table striped highlightOnHover>
            <Table.Thead>
              <Table.Tr>
                <Table.Th>{t('manageCertificates.subject', 'Subject')}</Table.Th>
                <Table.Th>{t('manageCertificates.issuer', 'Issuer')}</Table.Th>
                <Table.Th>{t('manageCertificates.validUntil', 'Valid until')}</Table.Th>
                <Table.Th>{t('manageCertificates.type', 'Type')}</Table.Th>
                <Table.Th ta="right">{t('manageCertificates.actions', 'Actions')}</Table.Th>
              </Table.Tr>
            </Table.Thead>
            <Table.Tbody>
              {certificates.map(certificate => {
                const expired = new Date(certificate.validUntil).getTime() < Date.now();
                return (
                  <Table.Tr key={certificate.fingerprint}>
                    <Table.Td>
                      <Text size="sm" fw={500}>{subjectName(certificate.subject)}</Text>
                      <Text size="xs" c="dimmed">{certificate.fingerprint}</Text>
                    </Table.Td>
                    <Table.Td><Text size="sm">{subjectName(certificate.issuer)}</Text></Table.Td>
                    <Table.Td>
                      <Text size="sm" c={expired ? 'red' : undefined}>
                        {new Date(certificate.validUntil).toLocaleDateString()}
                      </Text>
                    </Table.Td>
                    <Table.Td>
                      <Badge color={expired ? 'red' : certificate.certificateAuthority ? 'blue' : 'gray'} variant="light">
                        {expired
                          ? t('manageCertificates.expired', 'Expired')
                          : certificate.certificateAuthority
                            ? t('manageCertificates.ca', 'Certificate authority')
                            : t('manageCertificates.signer', 'Signer')}
                      </Badge>
                    </Table.Td>
                    <Table.Td>
                      <Group gap="xs" justify="flex-end" wrap="nowrap">
                        <Tooltip label={t('manageCertificates.export', 'Export')}>
                          <ActionIcon
                            component="a"
                            href={signingTrustCertificateUrl(certificate.fingerprint)}
                            variant="subtle"
                            aria-label={t('manageCertificates.export', 'Export')}
                          >
                            <DownloadOutlinedIcon fontSize="small" />
                          </ActionIcon>
                        </Tooltip>
                        <Tooltip label={t('manageCertificates.delete', 'Delete')}>
                          <ActionIcon
                            color="red"
                            variant="subtle"
                            onClick={() => void remove(certificate)}
                            disabled={busy}
                            aria-label={t('manageCertificates.delete', 'Delete')}
                          >
                            <DeleteOutlineRoundedIcon fontSize="small" />
                          </ActionIcon>
                        </Tooltip>
                      </Group>
                    </Table.Td>
                  </Table.Tr>
                );
              })}
              {certificates.length === 0 && (
                <Table.Tr>
                  <Table.Td colSpan={5}>
                    <Text ta="center" c="dimmed" py="xl">
                      {t('manageCertificates.empty', 'No managed trust certificates')}
                    </Text>
                  </Table.Td>
                </Table.Tr>
              )}
            </Table.Tbody>
          </Table>
        </Table.ScrollContainer>
      )}
    </Stack>
  );
};

export default ManageCertificates;
