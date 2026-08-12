import { useMemo } from 'react';
import { Box, SegmentedControl } from '@mantine/core';
import { useTranslation } from 'react-i18next';
import { useAppConfig } from '@app/contexts/AppConfigContext';
import { useToolWorkflow } from '@app/contexts/ToolWorkflowContext';
import type { ToolAvailabilityMap } from '@app/hooks/useToolManagement';
import type { ToolRegistry, ToolRegistryEntry } from '@app/data/toolsTaxonomy';
import { isValidToolId, type ToolId } from '@app/types/toolId';
import { getToolDisabledReason } from '@app/components/tools/fullscreen/shared';

export interface WorkspaceToolOption {
  toolId: ToolId;
  label: string;
  order: number;
  disabled: boolean;
}

export function getWorkspaceToolOptions(
  selectedToolId: ToolId | null,
  registry: Partial<ToolRegistry>,
  toolAvailability?: ToolAvailabilityMap,
  premiumEnabled?: boolean
): WorkspaceToolOption[] {
  if (!selectedToolId) return [];

  const selectedWorkspace = registry[selectedToolId]?.workspace;
  if (!selectedWorkspace) return [];

  return Object.entries(registry)
    .flatMap(([rawToolId, tool]) => {
      if (
        !tool ||
        !isValidToolId(rawToolId) ||
        tool.workspace?.id !== selectedWorkspace.id
      ) {
        return [];
      }

      return [
        {
          toolId: rawToolId,
          label: tool.workspace.label,
          order: tool.workspace.order,
          disabled:
            getToolDisabledReason(
              rawToolId,
              tool as ToolRegistryEntry,
              toolAvailability,
              premiumEnabled
            ) !== null,
        },
      ];
    })
    .sort((left, right) => left.order - right.order);
}

export default function ToolWorkspaceSwitcher() {
  const { t } = useTranslation();
  const { config } = useAppConfig();
  const {
    selectedToolKey,
    toolRegistry,
    toolAvailability,
    handleToolSelect,
  } = useToolWorkflow();

  const options = useMemo(
    () =>
      getWorkspaceToolOptions(
        selectedToolKey,
        toolRegistry,
        toolAvailability,
        config?.premiumEnabled
      ),
    [config?.premiumEnabled, selectedToolKey, toolAvailability, toolRegistry]
  );

  if (!selectedToolKey || options.length < 2) return null;

  return (
    <Box
      px="xs"
      py={6}
      style={{
        borderBottom: '1px solid var(--tool-panel-search-border-bottom)',
        background: 'var(--tool-panel-search-bg)',
        flexShrink: 0,
      }}
    >
      <SegmentedControl
        aria-label={t('signingWorkspace.modeLabel', 'Signing mode')}
        value={selectedToolKey}
        onChange={(value) => {
          if (isValidToolId(value) && value !== selectedToolKey) {
            handleToolSelect(value);
          }
        }}
        data={options.map((option) => ({
          value: option.toolId,
          label: option.label,
          disabled: option.disabled,
        }))}
        fullWidth
        size="xs"
        styles={{
          label: {
            paddingInline: 5,
            fontSize: '0.69rem',
            whiteSpace: 'nowrap',
          },
        }}
      />
    </Box>
  );
}
