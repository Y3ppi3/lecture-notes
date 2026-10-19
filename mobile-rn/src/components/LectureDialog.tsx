import { useState } from 'react';
import { Text, TextInput, View } from 'react-native';
import { colors } from './theme';
import { Button, Dialog, ui } from './ui';

export function LectureDialog({
  visible, title, submitLabel, initialTitle = '', initialSubject = '', onCancel, onSubmit,
}: {
  visible: boolean; title: string; submitLabel: string; initialTitle?: string; initialSubject?: string;
  onCancel: () => void; onSubmit: (title: string, subject: string) => void;
}) {
  return (
    <Dialog visible={visible} title={title} onClose={onCancel}>
      {visible && (
        <LectureForm
          initialTitle={initialTitle} initialSubject={initialSubject}
          submitLabel={submitLabel} onCancel={onCancel} onSubmit={onSubmit}
        />
      )}
    </Dialog>
  );
}

function LectureForm({
  initialTitle, initialSubject, submitLabel, onCancel, onSubmit,
}: {
  initialTitle: string; initialSubject: string; submitLabel: string;
  onCancel: () => void; onSubmit: (title: string, subject: string) => void;
}) {
  const [t, setT] = useState(initialTitle);
  const [sub, setSub] = useState(initialSubject);
  return (
    <>
      <TextInput style={ui.input} value={t} onChangeText={setT} placeholder="Тема лекции" autoFocus />
      <TextInput style={ui.input} value={sub} onChangeText={setSub} placeholder="Предмет (необязательно)" />
      <Text style={{ color: colors.muted, fontSize: 13 }}>
        Тема и предмет помогают ИИ правильно распознавать термины.
      </Text>
      <View style={ui.dialogActions}>
        <Button title="Отмена" variant="ghost" onPress={onCancel} />
        <Button title={submitLabel} onPress={() => onSubmit(t, sub)} />
      </View>
    </>
  );
}
