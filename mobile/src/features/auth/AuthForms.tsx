import { zodResolver } from '@hookform/resolvers/zod';
import { Link } from 'expo-router';
import { useState } from 'react';
import { Controller, useForm } from 'react-hook-form';
import { useTranslation } from 'react-i18next';
import { KeyboardAvoidingView, Platform, ScrollView, StyleSheet, Text, View } from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';

import { Button, ErrorBanner, TextField } from '../../components/ui';
import { errorMessage } from '../../i18n/format';
import { useAppTheme } from '../../theme/useAppTheme';
import { login, register } from './api';
import { loginSchema, registerSchema, type LoginValues, type RegisterValues } from './validation';

function AuthLayout({ title, children }: { title: string; children: React.ReactNode }) {
  const { colors } = useAppTheme();
  const { t } = useTranslation();
  return (
    <SafeAreaView style={[styles.safe, { backgroundColor: colors.background }]}>
      <KeyboardAvoidingView
        style={styles.safe}
        behavior={Platform.OS === 'ios' ? 'padding' : undefined}
      >
        <ScrollView contentContainerStyle={styles.content} keyboardShouldPersistTaps="handled">
          <View style={styles.header}>
            <Text style={[styles.brand, { color: colors.primary }]}>{t('appName')}</Text>
            <Text style={[styles.welcome, { color: colors.textMuted }]}>{t('auth.welcome')}</Text>
          </View>
          <Text accessibilityRole="header" style={[styles.title, { color: colors.text }]}>
            {title}
          </Text>
          {children}
        </ScrollView>
      </KeyboardAvoidingView>
    </SafeAreaView>
  );
}

export function LoginForm() {
  const { t } = useTranslation();
  const [serverError, setServerError] = useState<string | null>(null);
  const {
    control,
    handleSubmit,
    formState: { errors, isSubmitting },
  } = useForm<LoginValues>({
    resolver: zodResolver(loginSchema),
    defaultValues: { email: '', password: '' },
  });

  const onSubmit = handleSubmit(async (values) => {
    setServerError(null);
    try {
      await login(values.email, values.password);
    } catch (error) {
      setServerError(errorMessage(t, error));
    }
  });

  return (
    <AuthLayout title={t('auth.loginTitle')}>
      {serverError ? <ErrorBanner message={serverError} /> : null}
      <Controller
        control={control}
        name="email"
        render={({ field }) => (
          <TextField
            label={t('auth.email')}
            value={field.value}
            onChangeText={field.onChange}
            onBlur={field.onBlur}
            autoCapitalize="none"
            autoComplete="email"
            keyboardType="email-address"
            textContentType="emailAddress"
            error={errors.email ? t('auth.validation.email') : undefined}
            testID="login-email"
          />
        )}
      />
      <Controller
        control={control}
        name="password"
        render={({ field }) => (
          <TextField
            label={t('auth.password')}
            value={field.value}
            onChangeText={field.onChange}
            onBlur={field.onBlur}
            secureTextEntry
            autoComplete="current-password"
            textContentType="password"
            error={errors.password ? t('auth.validation.passwordRequired') : undefined}
            testID="login-password"
          />
        )}
      />
      <Button
        label={t('auth.loginButton')}
        onPress={onSubmit}
        loading={isSubmitting}
        testID="login-submit"
      />
      <Link href="/register" replace style={styles.link}>
        <LinkText>{t('auth.toRegister')}</LinkText>
      </Link>
    </AuthLayout>
  );
}

export function RegisterForm() {
  const { t } = useTranslation();
  const [serverError, setServerError] = useState<string | null>(null);
  const {
    control,
    handleSubmit,
    formState: { errors, isSubmitting },
  } = useForm<RegisterValues>({
    resolver: zodResolver(registerSchema),
    defaultValues: { displayName: '', email: '', password: '' },
  });

  const onSubmit = handleSubmit(async (values) => {
    setServerError(null);
    try {
      await register(values);
    } catch (error) {
      setServerError(errorMessage(t, error));
    }
  });

  const passwordError = errors.password
    ? errors.password.message === 'bytes'
      ? t('auth.validation.passwordBytes')
      : t('auth.validation.password')
    : undefined;

  return (
    <AuthLayout title={t('auth.registerTitle')}>
      {serverError ? <ErrorBanner message={serverError} /> : null}
      <Controller
        control={control}
        name="displayName"
        render={({ field }) => (
          <TextField
            label={t('auth.displayName')}
            value={field.value}
            onChangeText={field.onChange}
            onBlur={field.onBlur}
            autoComplete="name"
            textContentType="name"
            error={errors.displayName ? t('auth.validation.displayName') : undefined}
            testID="register-name"
          />
        )}
      />
      <Controller
        control={control}
        name="email"
        render={({ field }) => (
          <TextField
            label={t('auth.email')}
            value={field.value}
            onChangeText={field.onChange}
            onBlur={field.onBlur}
            autoCapitalize="none"
            autoComplete="email"
            keyboardType="email-address"
            textContentType="emailAddress"
            error={errors.email ? t('auth.validation.email') : undefined}
            testID="register-email"
          />
        )}
      />
      <Controller
        control={control}
        name="password"
        render={({ field }) => (
          <TextField
            label={t('auth.password')}
            value={field.value}
            onChangeText={field.onChange}
            onBlur={field.onBlur}
            secureTextEntry
            autoComplete="new-password"
            textContentType="newPassword"
            hint={t('auth.passwordHint')}
            error={passwordError}
            testID="register-password"
          />
        )}
      />
      <Button
        label={t('auth.registerButton')}
        onPress={onSubmit}
        loading={isSubmitting}
        testID="register-submit"
      />
      <Link href="/login" replace style={styles.link}>
        <LinkText>{t('auth.toLogin')}</LinkText>
      </Link>
    </AuthLayout>
  );
}

function LinkText({ children }: { children: string }) {
  const { colors } = useAppTheme();
  return <Text style={{ color: colors.primary, fontWeight: '600' }}>{children}</Text>;
}

const styles = StyleSheet.create({
  safe: { flex: 1 },
  content: { flexGrow: 1, justifyContent: 'center', padding: 24, gap: 16 },
  header: { alignItems: 'center', gap: 6, marginBottom: 16 },
  brand: { fontSize: 28, fontWeight: '800' },
  welcome: { fontSize: 15, textAlign: 'center' },
  title: { fontSize: 22, fontWeight: '700' },
  link: { alignSelf: 'center', paddingVertical: 8 },
});
