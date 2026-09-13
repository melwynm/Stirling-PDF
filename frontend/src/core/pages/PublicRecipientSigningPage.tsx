import { PreferencesProvider } from '@app/contexts/PreferencesContext';
import { RainbowThemeProvider } from '@app/components/shared/RainbowThemeProvider';
import RecipientSigningPage from '@app/pages/RecipientSigningPage';

export default function PublicRecipientSigningPage() {
  return (
    <PreferencesProvider>
      <RainbowThemeProvider><RecipientSigningPage /></RainbowThemeProvider>
    </PreferencesProvider>
  );
}
