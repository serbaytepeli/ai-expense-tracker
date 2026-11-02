import Ionicons from '@expo/vector-icons/Ionicons';
import { useEffect } from 'react';
import { useTranslation } from 'react-i18next';
import { StyleSheet, Text, View } from 'react-native';

import { useAppTheme } from '../../theme/useAppTheme';
import { useFlash } from './flash';

const VISIBLE_MS = 3000;

/** Success notice after saving or deleting an expense; hides itself after a few seconds. */
export function FlashBanner() {
  const { t } = useTranslation();
  const { colors } = useAppTheme();
  const message = useFlash((s) => s.message);
  const clear = useFlash((s) => s.clear);

  useEffect(() => {
    if (!message) {
      return;
    }
    const timer = setTimeout(clear, VISIBLE_MS);
    return () => clearTimeout(timer);
  }, [message, clear]);

  if (!message) {
    return null;
  }
  return (
    <View
      accessibilityLiveRegion="polite"
      style={[styles.banner, { backgroundColor: colors.surface, borderColor: colors.success }]}
      testID="flash-banner"
    >
      <Ionicons name="checkmark-circle" size={20} style={{ color: colors.success }} />
      <Text style={{ color: colors.text }}>
        {message === 'saved' ? t('expenses.saved') : t('expenses.deleted')}
      </Text>
    </View>
  );
}

const styles = StyleSheet.create({
  banner: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 8,
    padding: 12,
    borderRadius: 12,
    borderWidth: 1,
  },
});
