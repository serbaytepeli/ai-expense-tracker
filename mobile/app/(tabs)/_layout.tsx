import Ionicons from '@expo/vector-icons/Ionicons';
import { Tabs } from 'expo-router';
import type { ComponentProps } from 'react';
import { useTranslation } from 'react-i18next';
import type { ColorValue } from 'react-native';

import { useAppTheme } from '../../src/theme/useAppTheme';

type IconName = ComponentProps<typeof Ionicons>['name'];

function tabIcon(name: IconName) {
  return function TabIcon({ color, size }: { color: ColorValue; size: number }) {
    // `style.color` accepts any ColorValue the tab bar passes in.
    return <Ionicons name={name} size={size} style={{ color }} />;
  };
}

export default function TabsLayout() {
  const { t } = useTranslation();
  const { colors } = useAppTheme();

  return (
    <Tabs
      screenOptions={{
        tabBarActiveTintColor: colors.primary,
        tabBarInactiveTintColor: colors.tabInactive,
      }}
    >
      <Tabs.Screen
        name="index"
        options={{
          title: t('tabs.home'),
          tabBarIcon: tabIcon('home-outline'),
          tabBarButtonTestID: 'tab-home',
        }}
      />
      <Tabs.Screen
        name="expenses"
        options={{
          title: t('tabs.expenses'),
          tabBarIcon: tabIcon('wallet-outline'),
          tabBarButtonTestID: 'tab-expenses',
        }}
      />
      <Tabs.Screen
        name="report"
        options={{
          title: t('tabs.report'),
          tabBarIcon: tabIcon('pie-chart-outline'),
          tabBarButtonTestID: 'tab-report',
        }}
      />
      <Tabs.Screen
        name="settings"
        options={{
          title: t('tabs.settings'),
          tabBarIcon: tabIcon('settings-outline'),
          tabBarButtonTestID: 'tab-settings',
        }}
      />
    </Tabs>
  );
}
