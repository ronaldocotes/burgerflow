import React, { useRef, useState } from 'react';
import {
  ActivityIndicator,
  KeyboardAvoidingView,
  Platform,
  Pressable,
  ScrollView,
  StyleSheet,
  Text,
  TextInput,
  View,
} from 'react-native';
import { useNavigation } from '@react-navigation/native';
import { NativeStackNavigationProp } from '@react-navigation/native-stack';

import { post, ApiError } from '@/lib/api';
import { saveSession } from '@/lib/auth';
import { isDriverToken } from '@/lib/jwt';
import { RootStackParamList } from '@/navigation/RootNavigator';

// ---------------------------------------------------------------------------
// Tipos
// ---------------------------------------------------------------------------
type LoginNav = NativeStackNavigationProp<RootStackParamList, 'Login'>;

interface LoginResponse {
  token: string;
  refreshToken: string;
  tokenType: string;
  expiresIn: number;
}

// ---------------------------------------------------------------------------
// Tokens de cor (nao inventar outros — definidos no sistema de design)
// ---------------------------------------------------------------------------
const C = {
  bg: '#FFFFFF',
  bgSurface: '#F8FAFC',
  primary: '#047857',   // primary-700
  text: '#0F172A',
  textSecondary: '#475569',
  textMuted: '#94A3B8',
  error: '#EF4444',
  border: '#E2E8F0',
  borderFocus: '#047857',
} as const;

// ---------------------------------------------------------------------------
// Validacao simples (sem biblioteca externa para M0)
// ---------------------------------------------------------------------------
function validate(slug: string, email: string, password: string) {
  const errors: { slug?: string; email?: string; password?: string } = {};
  if (!slug.trim()) {
    errors.slug = 'Informe o identificador do restaurante.';
  }
  if (!email.trim()) {
    errors.email = 'Informe o e-mail.';
  } else if (!/\S+@\S+\.\S+/.test(email)) {
    errors.email = 'E-mail invalido.';
  }
  if (!password) {
    errors.password = 'Informe a senha.';
  }
  return errors;
}

// ---------------------------------------------------------------------------
// Componente de campo reutilizavel (label + input + erro inline)
// ---------------------------------------------------------------------------
interface FieldProps {
  label: string;
  value: string;
  onChangeText: (v: string) => void;
  error?: string;
  placeholder?: string;
  keyboardType?: React.ComponentProps<typeof TextInput>['keyboardType'];
  autoCapitalize?: React.ComponentProps<typeof TextInput>['autoCapitalize'];
  secureTextEntry?: boolean;
  textContentType?: React.ComponentProps<typeof TextInput>['textContentType'];
  autoComplete?: React.ComponentProps<typeof TextInput>['autoComplete'];
  returnKeyType?: React.ComponentProps<typeof TextInput>['returnKeyType'];
  onSubmitEditing?: () => void;
  inputRef?: React.RefObject<TextInput | null>;
  editable?: boolean;
}

function Field({
  label,
  value,
  onChangeText,
  error,
  placeholder,
  keyboardType = 'default',
  autoCapitalize = 'sentences',
  secureTextEntry = false,
  textContentType,
  autoComplete,
  returnKeyType = 'next',
  onSubmitEditing,
  inputRef,
  editable = true,
}: FieldProps) {
  const [focused, setFocused] = useState(false);

  return (
    <View style={fStyles.wrapper}>
      <Text style={fStyles.label}>{label}</Text>
      <TextInput
        ref={inputRef}
        style={[
          fStyles.input,
          focused && fStyles.inputFocused,
          !!error && fStyles.inputError,
          !editable && fStyles.inputDisabled,
        ]}
        value={value}
        onChangeText={onChangeText}
        placeholder={placeholder}
        placeholderTextColor={C.textMuted}
        keyboardType={keyboardType}
        autoCapitalize={autoCapitalize}
        secureTextEntry={secureTextEntry}
        textContentType={textContentType}
        autoComplete={autoComplete}
        returnKeyType={returnKeyType}
        onSubmitEditing={onSubmitEditing}
        blurOnSubmit={returnKeyType === 'done'}
        onFocus={() => setFocused(true)}
        onBlur={() => setFocused(false)}
        editable={editable}
        accessibilityLabel={label}
      />
      {!!error && (
        <Text style={fStyles.errorText} accessibilityRole="alert">
          {error}
        </Text>
      )}
    </View>
  );
}

const fStyles = StyleSheet.create({
  wrapper: { marginBottom: 16 },
  label: {
    fontSize: 14,
    fontWeight: '600',
    color: C.text,
    marginBottom: 6,
  },
  input: {
    height: 48,
    borderWidth: 1.5,
    borderColor: C.border,
    borderRadius: 10,
    paddingHorizontal: 14,
    fontSize: 15,
    color: C.text,
    backgroundColor: C.bg,
  },
  inputFocused: { borderColor: C.borderFocus },
  inputError: { borderColor: C.error },
  inputDisabled: { backgroundColor: '#F1F5F9', color: C.textMuted },
  errorText: {
    marginTop: 4,
    fontSize: 12,
    color: C.error,
    fontWeight: '500',
  },
});

// ---------------------------------------------------------------------------
// LoginScreen
// ---------------------------------------------------------------------------
export default function LoginScreen() {
  const navigation = useNavigation<LoginNav>();

  const [slug, setSlug] = useState('');
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');

  const [errors, setErrors] = useState<{
    slug?: string;
    email?: string;
    password?: string;
    form?: string;
  }>({});

  const [loading, setLoading] = useState(false);

  const emailRef = useRef<TextInput>(null);
  const passwordRef = useRef<TextInput>(null);

  async function handleLogin() {
    const fieldErrors = validate(slug, email, password);
    if (Object.keys(fieldErrors).length > 0) {
      setErrors(fieldErrors);
      return;
    }
    setErrors({});
    setLoading(true);

    try {
      const res = await post<LoginResponse>('/auth/login', {
        email: email.trim(),
        password,
        tenantSlug: slug.trim(),
      });
      await saveSession(res.token, res.refreshToken, slug.trim());

      // Reseta o stack — impede voltar ao Login com backswipe
      navigation.reset({
        index: 0,
        routes: [{ name: isDriverToken(res.token) ? 'DriverTabs' : 'Tabs' }],
      });
    } catch (err) {
      if (err instanceof ApiError) {
        setErrors({
          form:
            err.status === 401
              ? 'E-mail, senha ou restaurante invalidos.'
              : 'Nao foi possivel entrar. Tente novamente.',
        });
      } else {
        setErrors({ form: 'Nao foi possivel entrar. Verifique a conexao.' });
      }
    } finally {
      setLoading(false);
    }
  }

  return (
    <KeyboardAvoidingView
      style={styles.flex}
      behavior={Platform.OS === 'ios' ? 'padding' : 'padding'}
      keyboardVerticalOffset={0}
    >
      <ScrollView
        contentContainerStyle={styles.scroll}
        keyboardShouldPersistTaps="handled"
        showsVerticalScrollIndicator={false}
      >
        {/* Logo — substituir por <Image> quando o asset existir */}
        <View style={styles.logoArea}>
          <Text style={styles.logoBrand}>MF</Text>
          <Text style={styles.logoName}>MenuFlow</Text>
          <Text style={styles.logoSub}>Entrar no painel</Text>
        </View>

        {/* Card do formulario */}
        <View style={styles.card}>
          <Field
            label="Restaurante"
            value={slug}
            onChangeText={(v) => {
              setSlug(v);
              if (errors.slug) setErrors((e) => ({ ...e, slug: undefined }));
            }}
            error={errors.slug}
            placeholder="ex.: minha-hamburgueria"
            autoCapitalize="none"
            textContentType="organizationName"
            autoComplete="organization"
            returnKeyType="next"
            onSubmitEditing={() => emailRef.current?.focus()}
            editable={!loading}
          />

          <Field
            label="E-mail"
            value={email}
            onChangeText={(v) => {
              setEmail(v);
              if (errors.email) setErrors((e) => ({ ...e, email: undefined }));
            }}
            error={errors.email}
            placeholder="voce@restaurante.com"
            keyboardType="email-address"
            autoCapitalize="none"
            textContentType="emailAddress"
            autoComplete="email"
            returnKeyType="next"
            onSubmitEditing={() => passwordRef.current?.focus()}
            inputRef={emailRef}
            editable={!loading}
          />

          <Field
            label="Senha"
            value={password}
            onChangeText={(v) => {
              setPassword(v);
              if (errors.password)
                setErrors((e) => ({ ...e, password: undefined }));
            }}
            error={errors.password}
            secureTextEntry
            textContentType="password"
            autoComplete="password"
            returnKeyType="done"
            onSubmitEditing={handleLogin}
            inputRef={passwordRef}
            editable={!loading}
          />

          {/* Erro de formulario global */}
          {!!errors.form && (
            <View style={styles.formErrorBox}>
              <Text style={styles.formErrorText} accessibilityRole="alert">
                {errors.form}
              </Text>
            </View>
          )}

          {/* CTA — minimo 52dp (>48dp exigido para touch targets) */}
          <Pressable
            style={({ pressed }) => [
              styles.btnPrimary,
              (loading || pressed) && styles.btnPrimaryPressed,
            ]}
            onPress={handleLogin}
            disabled={loading}
            accessibilityRole="button"
            accessibilityLabel="Entrar"
            accessibilityState={{ disabled: loading, busy: loading }}
          >
            {loading ? (
              <ActivityIndicator color="#FFFFFF" size="small" />
            ) : (
              <Text style={styles.btnPrimaryLabel}>Entrar</Text>
            )}
          </Pressable>
        </View>
      </ScrollView>
    </KeyboardAvoidingView>
  );
}

// ---------------------------------------------------------------------------
// Estilos
// ---------------------------------------------------------------------------
const styles = StyleSheet.create({
  flex: { flex: 1, backgroundColor: C.bgSurface },
  scroll: {
    flexGrow: 1,
    justifyContent: 'center',
    paddingHorizontal: 24,
    paddingVertical: 32,
  },

  // Logo
  logoArea: { alignItems: 'center', marginBottom: 32 },
  logoBrand: {
    fontSize: 52,
    fontWeight: '800',
    color: C.primary,
    letterSpacing: -2,
  },
  logoName: {
    fontSize: 22,
    fontWeight: '700',
    color: C.text,
    marginTop: 2,
  },
  logoSub: {
    fontSize: 14,
    color: C.textSecondary,
    marginTop: 4,
  },

  // Card
  card: {
    backgroundColor: C.bg,
    borderRadius: 16,
    padding: 24,
    shadowColor: '#000',
    shadowOffset: { width: 0, height: 2 },
    shadowOpacity: 0.07,
    shadowRadius: 12,
    elevation: 3,
  },

  // Erro global
  formErrorBox: {
    backgroundColor: '#FEF2F2',
    borderRadius: 8,
    padding: 12,
    marginBottom: 16,
    borderWidth: 1,
    borderColor: '#FECACA',
  },
  formErrorText: {
    color: C.error,
    fontSize: 13,
    fontWeight: '500',
  },

  // Botao primario
  btnPrimary: {
    height: 52,
    borderRadius: 10,
    backgroundColor: C.primary,
    justifyContent: 'center',
    alignItems: 'center',
    marginTop: 4,
  },
  btnPrimaryPressed: { opacity: 0.85 },
  btnPrimaryLabel: {
    color: '#FFFFFF',
    fontSize: 16,
    fontWeight: '700',
    letterSpacing: 0.3,
  },
});
