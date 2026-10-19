import { Ionicons } from '@expo/vector-icons';
import { useState, type ComponentProps, type ReactNode } from 'react';
import {
  KeyboardAvoidingView,
  Modal,
  Platform,
  Pressable,
  StyleSheet,
  Text,
  TextInput,
  View,
} from 'react-native';
import { colors } from './theme';

export type IconName = ComponentProps<typeof Ionicons>['name'];

export function IconButton({
  name, onPress, color = colors.text, size = 24, label, disabled,
}: { name: IconName; onPress: () => void; color?: string; size?: number; label: string; disabled?: boolean }) {
  return (
    <Pressable
      onPress={onPress}
      disabled={disabled}
      accessibilityLabel={label}
      hitSlop={8}
      style={({ pressed }) => [ui.iconBtn, pressed && { opacity: 0.5 }, disabled && { opacity: 0.3 }]}
    >
      <Ionicons name={name} size={size} color={color} />
    </Pressable>
  );
}

export function Button({
  title, onPress, variant = 'primary', icon, disabled,
}: {
  title: string; onPress: () => void; variant?: 'primary' | 'danger' | 'ghost'; icon?: IconName; disabled?: boolean;
}) {
  const bg = variant === 'primary' ? colors.primary : variant === 'danger' ? colors.danger : 'transparent';
  const fg = variant === 'ghost' ? colors.primary : '#fff';
  return (
    <Pressable
      onPress={onPress}
      disabled={disabled}
      style={({ pressed }) => [ui.btn, { backgroundColor: bg }, pressed && { opacity: 0.7 }, disabled && { opacity: 0.4 }]}
    >
      {icon && <Ionicons name={icon} size={18} color={fg} />}
      <Text style={[ui.btnText, { color: fg }]}>{title}</Text>
    </Pressable>
  );
}

/** Модальное окно-карточка (диалог). */
export function Dialog({
  visible, title, onClose, children, actions,
}: { visible: boolean; title: string; onClose: () => void; children?: ReactNode; actions?: ReactNode }) {
  return (
    <Modal visible={visible} transparent animationType="fade" onRequestClose={onClose}>
      <KeyboardAvoidingView behavior={Platform.OS === 'ios' ? 'padding' : undefined} style={ui.backdrop}>
        <Pressable style={StyleSheet.absoluteFill} onPress={onClose} />
        <View style={ui.dialog}>
          <Text style={ui.dialogTitle}>{title}</Text>
          {children}
          {actions && <View style={ui.dialogActions}>{actions}</View>}
        </View>
      </KeyboardAvoidingView>
    </Modal>
  );
}

/** Диалог ввода текста. */
export function TextPrompt({
  visible, title, initial = '', placeholder, multiline, onCancel, onSubmit, submitLabel = 'Сохранить',
}: {
  visible: boolean; title: string; initial?: string; placeholder?: string; multiline?: boolean;
  onCancel: () => void; onSubmit: (v: string) => void; submitLabel?: string;
}) {
  return (
    <Dialog visible={visible} title={title} onClose={onCancel}>
      {visible && (
        <PromptBody
          initial={initial} placeholder={placeholder} multiline={multiline}
          onCancel={onCancel} onSubmit={onSubmit} submitLabel={submitLabel}
        />
      )}
    </Dialog>
  );
}

function PromptBody({
  initial, placeholder, multiline, onCancel, onSubmit, submitLabel,
}: { initial: string; placeholder?: string; multiline?: boolean; onCancel: () => void; onSubmit: (v: string) => void; submitLabel: string }) {
  const [value, setValue] = useState(initial);
  return (
    <>
      <TextInput
        value={value}
        onChangeText={setValue}
        placeholder={placeholder}
        multiline={multiline}
        autoFocus
        style={[ui.input, multiline && { minHeight: 140, maxHeight: 360, textAlignVertical: 'top' }]}
      />
      <View style={ui.dialogActions}>
        <Button title="Отмена" variant="ghost" onPress={onCancel} />
        <Button title={submitLabel} onPress={() => onSubmit(value)} />
      </View>
    </>
  );
}

export interface MenuItem {
  label: string;
  icon: IconName;
  onPress: () => void;
  danger?: boolean;
}

/** Нижнее меню действий. */
export function ActionSheet({
  visible, title, items, onClose,
}: { visible: boolean; title?: string; items: MenuItem[]; onClose: () => void }) {
  return (
    <Modal visible={visible} transparent animationType="slide" onRequestClose={onClose}>
      <Pressable style={ui.sheetBackdrop} onPress={onClose}>
        <View style={ui.sheet}>
          {title && <Text style={ui.sheetTitle}>{title}</Text>}
          {items.map((it) => (
            <Pressable
              key={it.label}
              onPress={() => { onClose(); it.onPress(); }}
              style={({ pressed }) => [ui.sheetItem, pressed && { backgroundColor: colors.bg }]}
            >
              <Ionicons name={it.icon} size={22} color={it.danger ? colors.danger : colors.text} />
              <Text style={[ui.sheetText, it.danger && { color: colors.danger }]}>{it.label}</Text>
            </Pressable>
          ))}
        </View>
      </Pressable>
    </Modal>
  );
}

export const ui = StyleSheet.create({
  iconBtn: { padding: 6 },
  btn: {
    flexDirection: 'row', alignItems: 'center', justifyContent: 'center', gap: 6,
    paddingHorizontal: 16, paddingVertical: 10, borderRadius: 20,
  },
  btnText: { fontSize: 15, fontWeight: '600' },
  backdrop: { flex: 1, backgroundColor: 'rgba(0,0,0,0.4)', justifyContent: 'center', padding: 24 },
  dialog: { backgroundColor: colors.card, borderRadius: 20, padding: 20, gap: 12 },
  dialogTitle: { fontSize: 19, fontWeight: '700', color: colors.text },
  dialogActions: { flexDirection: 'row', justifyContent: 'flex-end', gap: 8, marginTop: 4 },
  input: {
    borderWidth: 1, borderColor: colors.border, borderRadius: 12, paddingHorizontal: 12, paddingVertical: 10,
    fontSize: 16, color: colors.text,
  },
  sheetBackdrop: { flex: 1, backgroundColor: 'rgba(0,0,0,0.35)', justifyContent: 'flex-end' },
  sheet: { backgroundColor: colors.card, borderTopLeftRadius: 20, borderTopRightRadius: 20, paddingVertical: 8, paddingBottom: 28 },
  sheetTitle: { fontSize: 14, color: colors.muted, paddingHorizontal: 20, paddingVertical: 10 },
  sheetItem: { flexDirection: 'row', alignItems: 'center', gap: 16, paddingHorizontal: 20, paddingVertical: 14 },
  sheetText: { fontSize: 16, color: colors.text },
});
