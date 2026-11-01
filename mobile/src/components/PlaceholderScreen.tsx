import { StyleSheet, Text, View } from 'react-native';

import { useAppTheme } from '../theme/useAppTheme';

interface PlaceholderScreenProps {
  /** Already translated title. */
  title: string;
  /** Already translated description of what the screen will show. */
  message: string;
}

/** Empty screen used by the M0 skeleton until the feature is built. */
export function PlaceholderScreen({ title, message }: PlaceholderScreenProps) {
  const { colors } = useAppTheme();

  return (
    <View style={[styles.container, { backgroundColor: colors.background }]}>
      <Text accessibilityRole="header" style={[styles.title, { color: colors.text }]}>
        {title}
      </Text>
      <Text style={[styles.message, { color: colors.textMuted }]}>{message}</Text>
    </View>
  );
}

const styles = StyleSheet.create({
  container: {
    flex: 1,
    alignItems: 'center',
    justifyContent: 'center',
    paddingHorizontal: 24,
    gap: 8,
  },
  title: {
    fontSize: 22,
    fontWeight: '600',
  },
  message: {
    fontSize: 16,
    textAlign: 'center',
  },
});
