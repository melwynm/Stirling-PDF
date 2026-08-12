import { useTranslation } from "react-i18next";
import { createToolFlow } from "@app/components/tools/shared/createToolFlow";
import { useRemoveCertificateSignParameters } from "@app/hooks/tools/removeCertificateSign/useRemoveCertificateSignParameters";
import { useRemoveCertificateSignOperation } from "@app/hooks/tools/removeCertificateSign/useRemoveCertificateSignOperation";
import { useBaseTool } from "@app/hooks/tools/shared/useBaseTool";
import { BaseToolProps, ToolComponent } from "@app/types/tool";
import RemoveCertificateSignSettings from '@app/components/tools/removeCertificateSign/RemoveCertificateSignSettings';

const RemoveCertificateSign = (props: BaseToolProps) => {
  const { t } = useTranslation();

  const base = useBaseTool(
    'removeCertificateSign',
    useRemoveCertificateSignParameters,
    useRemoveCertificateSignOperation,
    props
  );

  return createToolFlow({
    files: {
      selectedFiles: base.selectedFiles,
      isCollapsed: base.hasResults,
    },
    steps: [{
      title: t('removeCertSign.settingsTitle', 'Signature handling'),
      isCollapsed: base.settingsCollapsed,
      onCollapsedClick: base.settingsCollapsed ? base.handleSettingsReset : undefined,
      content: (
        <RemoveCertificateSignSettings
          parameters={base.params.parameters}
          onParameterChange={base.params.updateParameter}
          disabled={base.endpointLoading}
        />
      ),
    }],
    executeButton: {
      text: t("removeCertSign.submit", "Apply"),
      isVisible: !base.hasResults,
      loadingText: t("loading"),
      onClick: base.handleExecute,
      disabled: !base.params.validateParameters() || !base.hasFiles || !base.endpointEnabled,
    },
    review: {
      isVisible: base.hasResults,
      operation: base.operation,
      title: t("removeCertSign.results.title", "Signature Handling Results"),
      onFileClick: base.handleThumbnailClick,
      onUndo: base.handleUndo,
    },
  });
};

// Static method to get the operation hook for automation
RemoveCertificateSign.tool = () => useRemoveCertificateSignOperation;

export default RemoveCertificateSign as ToolComponent;
