import { useTranslation } from 'react-i18next';

import { PlaceholderScreen } from '../../src/components/PlaceholderScreen';

export default function ReportScreen() {
  const { t } = useTranslation();
  return (
    <PlaceholderScreen
      title={t('screens.report.title')}
      message={t('screens.report.placeholder')}
    />
  );
}
