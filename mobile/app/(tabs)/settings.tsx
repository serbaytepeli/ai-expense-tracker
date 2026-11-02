import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { StyleSheet, Text, View } from 'react-native';

import { Button } from '../../src/components/ui';
import { logout } from '../../src/features/auth/api';
import { useSessionStore } from '../../src/features/auth/sessionStore';
import { useAppTheme } from '../../src/theme/useAppTheme';

export default function SettingsScreen() {
  const { t } = useTranslation();
  const { colors } = useAppTheme();
  const user = useSessionStore((s) => s.session?.user);
  const [busy, setBusy] = useState(false);

  return (
    <View style={[styles.container, { backgroundColor: colors.background }]}>
      <View style={[styles.card, { backgroundColor: colors.surface }]}>
        <Text style={{ color: colors.textMuted }}>{t('screens.settings.signedInAs')}</Text>
        <Text style={[styles.name, { color: colors.text }]}>{user?.displayName}</Text>
        <Text style={{ color: colors.textMuted }}>{user?.email}</Text>
      </View>
      <Button
        label={t('auth.logout')}
        variant="secondary"
        icon="log-out-outline"
        loading={busy}
        onPress={() => {
          setBusy(true);
          void logout();
        }}
        testID="logout"
      />
    </View>
  );
}

const styles = StyleSheet.create({
  container: { flex: 1, padding: 20, gap: 16 },
  card: { borderRadius: 16, padding: 16, gap: 4 },
  name: { fontSize: 18, fontWeight: '600' },
});
