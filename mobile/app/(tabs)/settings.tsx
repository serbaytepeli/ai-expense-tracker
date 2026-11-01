import { useTranslation } from 'react-i18next';

import { PlaceholderScreen } from '../../src/components/PlaceholderScreen';

export default function SettingsScreen() {
  const { t } = useTranslation();
  return (
    <PlaceholderScreen
      title={t('screens.settings.title')}
      message={t('screens.settings.placeholder')}
    />
  );
}
