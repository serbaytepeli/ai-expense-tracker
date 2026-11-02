import Ionicons from '@expo/vector-icons/Ionicons';
import type { ComponentProps, ReactNode } from 'react';
import { useTranslation } from 'react-i18next';
import {
  ActivityIndicator,
  Pressable,
  StyleSheet,
  Text,
  TextInput,
  View,
  type TextInputProps,
} from 'react-native';

import { useAppTheme } from '../theme/useAppTheme';

export type IconName = ComponentProps<typeof Ionicons>['name'];

/** Backend icon keys are Ionicons names; unknown ones fall back to a neutral icon. */
export function toIconName(icon: string): IconName {
  return icon in Ionicons.glyphMap ? (icon as IconName) : 'pricetag-outline';
}

interface ButtonProps {
  label: string;
  onPress: () => void;
  variant?: 'primary' | 'secondary' | 'danger';
  loading?: boolean;
  disabled?: boolean;
  icon?: IconName;
  testID?: string;
}

export function Button({
  label,
  onPress,
  variant = 'primary',
  loading,
  disabled,
  icon,
  testID,
}: ButtonProps) {
  const { colors } = useAppTheme();
  const background =
    variant === 'primary'
      ? colors.primary
      : variant === 'danger'
        ? colors.dangerSurface
        : 'transparent';
  const foreground =
    variant === 'primary'
      ? colors.onPrimary
      : variant === 'danger'
        ? colors.danger
        : colors.primary;
  const inactive = Boolean(disabled || loading);
  return (
    <Pressable
      accessibilityRole="button"
      accessibilityLabel={label}
      accessibilityState={{ disabled: inactive, busy: Boolean(loading) }}
      disabled={inactive}
      onPress={onPress}
      testID={testID}
      style={({ pressed }) => [
        styles.button,
        {
          backgroundColor: background,
          borderColor: variant === 'secondary' ? colors.border : background,
          opacity: inactive ? 0.6 : pressed ? 0.85 : 1,
        },
      ]}
    >
      {loading ? (
        <ActivityIndicator color={foreground} />
      ) : (
        <View style={styles.buttonContent}>
          {icon ? <Ionicons name={icon} size={20} style={{ color: foreground }} /> : null}
          <Text style={[styles.buttonText, { color: foreground }]}>{label}</Text>
        </View>
      )}
    </Pressable>
  );
}

interface FieldProps extends TextInputProps {
  label: string;
  error?: string;
  hint?: string;
}

export function TextField({ label, error, hint, style, ...input }: FieldProps) {
  const { colors } = useAppTheme();
  return (
    <View style={styles.field}>
      <Text style={[styles.label, { color: colors.textMuted }]}>{label}</Text>
      <TextInput
        accessibilityLabel={label}
        placeholderTextColor={colors.tabInactive}
        style={[
          styles.input,
          {
            color: colors.text,
            backgroundColor: colors.surface,
            borderColor: error ? colors.danger : colors.border,
          },
          style,
        ]}
        {...input}
      />
      {error ? (
        <Text style={[styles.helper, { color: colors.danger }]}>{error}</Text>
      ) : hint ? (
        <Text style={[styles.helper, { color: colors.textMuted }]}>{hint}</Text>
      ) : null}
    </View>
  );
}

export function FieldLabel({ children }: { children: ReactNode }) {
  const { colors } = useAppTheme();
  return <Text style={[styles.label, { color: colors.textMuted }]}>{children}</Text>;
}

export function FieldError({ message }: { message?: string }) {
  const { colors } = useAppTheme();
  return message ? <Text style={[styles.helper, { color: colors.danger }]}>{message}</Text> : null;
}

/** Error box with an optional retry button. */
export function ErrorBanner({ message, onRetry }: { message: string; onRetry?: () => void }) {
  const { colors } = useAppTheme();
  const { t } = useTranslation();
  return (
    <View
      accessibilityRole="alert"
      style={[styles.banner, { backgroundColor: colors.dangerSurface }]}
      testID="error-banner"
    >
      <Text style={{ color: colors.danger, flex: 1 }}>{message}</Text>
      {onRetry ? (
        <Pressable accessibilityRole="button" onPress={onRetry} hitSlop={8}>
          <Text style={[styles.bannerAction, { color: colors.danger }]}>{t('common.retry')}</Text>
        </Pressable>
      ) : null}
    </View>
  );
}

export function EmptyState({
  icon,
  title,
  message,
  children,
}: {
  icon: IconName;
  title: string;
  message: string;
  children?: ReactNode;
}) {
  const { colors } = useAppTheme();
  return (
    <View style={styles.empty}>
      <Ionicons name={icon} size={40} style={{ color: colors.tabInactive }} />
      <Text style={[styles.emptyTitle, { color: colors.text }]}>{title}</Text>
      <Text style={[styles.emptyMessage, { color: colors.textMuted }]}>{message}</Text>
      {children}
    </View>
  );
}

export function LoadingView() {
  const { colors } = useAppTheme();
  return (
    <View style={styles.loading}>
      <ActivityIndicator color={colors.primary} size="large" />
    </View>
  );
}

/** Round colored badge with the category's icon. */
export function CategoryBadge({
  icon,
  color,
  size = 40,
}: {
  icon: string;
  color: string;
  size?: number;
}) {
  return (
    <View
      style={[
        styles.badge,
        { width: size, height: size, borderRadius: size / 2, backgroundColor: `${color}22` },
      ]}
    >
      <Ionicons name={toIconName(icon)} size={size * 0.5} style={{ color }} />
    </View>
  );
}

const styles = StyleSheet.create({
  button: {
    minHeight: 50,
    borderRadius: 12,
    borderWidth: 1,
    paddingHorizontal: 16,
    alignItems: 'center',
    justifyContent: 'center',
  },
  buttonContent: { flexDirection: 'row', alignItems: 'center', gap: 8 },
  buttonText: { fontSize: 16, fontWeight: '600' },
  field: { gap: 6 },
  label: { fontSize: 14, fontWeight: '500' },
  input: {
    minHeight: 50,
    borderWidth: 1,
    borderRadius: 12,
    paddingHorizontal: 14,
    fontSize: 16,
  },
  helper: { fontSize: 13 },
  banner: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 12,
    padding: 12,
    borderRadius: 12,
  },
  bannerAction: { fontWeight: '700' },
  empty: { alignItems: 'center', gap: 8, paddingVertical: 48, paddingHorizontal: 24 },
  emptyTitle: { fontSize: 17, fontWeight: '600', textAlign: 'center' },
  emptyMessage: { fontSize: 15, textAlign: 'center' },
  loading: { flex: 1, alignItems: 'center', justifyContent: 'center', padding: 32 },
  badge: { alignItems: 'center', justifyContent: 'center' },
});
