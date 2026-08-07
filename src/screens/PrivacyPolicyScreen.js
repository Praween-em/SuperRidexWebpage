import React from 'react';
import {
  View, Text, StyleSheet, StatusBar, ScrollView, TouchableOpacity,
} from 'react-native';
import { Ionicons } from '@expo/vector-icons';
import Screen from '../components/Screen';
import { colors } from '../theme/colors';

function Section({ title, children }) {
  return (
    <View style={styles.section}>
      <Text style={styles.sectionTitle}>{title}</Text>
      {children}
    </View>
  );
}

function P({ children }) {
  return <Text style={styles.para}>{children}</Text>;
}

export default function PrivacyPolicyScreen({ navigation }) {
  return (
    <Screen edges={['top', 'bottom']}>
      <StatusBar barStyle="dark-content" backgroundColor={colors.background} />
      <View style={styles.header}>
        <TouchableOpacity
          style={styles.backBtn}
          onPress={() => navigation.goBack()}
          accessibilityRole="button"
          accessibilityLabel="Go back"
        >
          <Ionicons name="chevron-back" size={22} color={colors.icy} />
        </TouchableOpacity>
        <Text style={styles.headerTitle}>Privacy Policy</Text>
        <View style={styles.backBtn} />
      </View>

      <ScrollView contentContainerStyle={styles.scroll} showsVerticalScrollIndicator={false}>
        <Text style={styles.updated}>Last updated: 28 July 2026</Text>
        <Text style={styles.lead}>
          SUPER RIDEX (“we”, “our”, or “the App”) is designed as an accessibility
          assistive tool. This Privacy Policy explains what information we
          process, why we process it, and the choices available to you.
        </Text>

        <Section title="1. Purpose of the App">
          <P>
            SUPER RIDEX helps drivers who experience motor, coordination, vision,
            or related difficulties interact with ride-request screens more
            reliably. The App uses Android Accessibility Service capabilities so
            that eligible users can activate an on-screen Accept control when they
            are otherwise unable to press it accurately or in time.
          </P>
          <P>
            The App is intended solely for lawful accessibility assistance. It is
            not provided to interfere with third-party services, automate activity
            for users without a genuine accessibility need, or collect
            unnecessary personal data.
          </P>
        </Section>

        <Section title="2. Information We Process">
          <P>
            Depending on how you use SUPER RIDEX, we may process:
          </P>
          <P>
            • Account details you provide (such as your mobile number) for
            sign-in and subscription management.{"\n"}
            • Device identifiers required to enforce plan limits and protect
            accounts.{"\n"}
            • Subscription and payment status handled through our payment
            provider.{"\n"}
            • App preferences you set (for example, response delay).
          </P>
          <P>
            Accessibility Service access is used only to detect and activate the
            relevant Accept control for assistive purposes. We do not use that
            access to read your personal messages, contacts, photos, or unrelated
            app content for marketing or profiling.
          </P>
        </Section>

        <Section title="3. How We Use Information">
          <P>
            We use information to authenticate your account, deliver subscription
            features, operate the accessibility assistive functions you enable,
            improve reliability and support, and protect against misuse or fraud.
          </P>
        </Section>

        <Section title="4. Storage and Security">
          <P>
            Account and subscription data may be stored on secure cloud services
            operated by us or our processors. Local preferences may remain on
            your device. We apply reasonable technical and
            organisational measures to protect information under our control.
            No method of transmission or storage is completely secure, and we
            cannot guarantee absolute security.
          </P>
        </Section>

        <Section title="5. Sharing">
          <P>
            We do not sell your personal information. We may share limited data
            with service providers who help us operate authentication, payments,
            hosting, or support — only as needed for those services — or when
            required by law.
          </P>
        </Section>

        <Section title="6. Your Choices">
          <P>
            You may revoke Accessibility Service, overlay, notification, and
            related permissions at any time in Android Settings. You may also
            log out, or contact us to request account-related assistance where
            applicable.
          </P>
        </Section>

        <Section title="7. Children’s Privacy">
          <P>
            SUPER RIDEX is intended for adult professional drivers. It is not
            directed to children.
          </P>
        </Section>

        <Section title="8. Changes">
          <P>
            We may update this Privacy Policy from time to time. Continued use of
            the App after an update means you acknowledge the revised policy.
          </P>
        </Section>

        <Section title="9. Contact">
          <P>
            For privacy questions about SUPER RIDEX, contact us through the
            support channels listed in the App.
          </P>
        </Section>
      </ScrollView>
    </Screen>
  );
}

const styles = StyleSheet.create({
  header: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    paddingHorizontal: 12,
    paddingVertical: 12,
    borderBottomWidth: 1,
    borderBottomColor: colors.border,
  },
  backBtn: {
    width: 40,
    height: 40,
    alignItems: 'center',
    justifyContent: 'center',
  },
  headerTitle: {
    flex: 1,
    textAlign: 'center',
    fontSize: 17,
    fontWeight: '800',
    color: colors.icy,
  },
  scroll: { padding: 20, paddingBottom: 40, gap: 8 },
  updated: { fontSize: 12, color: colors.icyMuted, marginBottom: 8 },
  lead: {
    fontSize: 14,
    lineHeight: 22,
    color: colors.icyDim,
    marginBottom: 12,
  },
  section: { marginTop: 14, gap: 8 },
  sectionTitle: {
    fontSize: 15,
    fontWeight: '800',
    color: colors.icy,
    marginBottom: 2,
  },
  para: {
    fontSize: 14,
    lineHeight: 22,
    color: colors.icyDim,
  },
});
