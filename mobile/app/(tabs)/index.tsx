import { useTranslation } from 'react-i18next';

import { PlaceholderScreen } from '../../src/components/PlaceholderScreen';

export default function HomeScreen() {
  const { t } = useTranslation();
  return (
    <PlaceholderScreen title={t('screens.home.title')} message={t('screens.home.placeholder')} />
  );
}
