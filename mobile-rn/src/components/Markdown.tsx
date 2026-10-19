import { StyleSheet, Text, View } from 'react-native';
import { colors } from './theme';

/** Минимальный рендер Markdown из ИИ-коррекции: заголовки, списки, **жирный**, *курсив*. */
export function Markdown({ text }: { text: string }) {
  return (
    <View style={{ gap: 6 }}>
      {text.split('\n').map((raw, i) => {
        const line = raw.trimEnd();
        if (!line.trim()) return null;
        if (line.startsWith('### ')) return <Text key={i} style={s.h3}>{inline(line.slice(4))}</Text>;
        if (line.startsWith('## ')) return <Text key={i} style={s.h2}>{inline(line.slice(3))}</Text>;
        if (line.startsWith('# ')) return <Text key={i} style={s.h1}>{inline(line.slice(2))}</Text>;
        const bullet = /^(\s*)[-*•] (.*)$/.exec(line);
        if (bullet) {
          return (
            <View key={i} style={[s.row, { paddingLeft: Math.floor(bullet[1].length / 2) * 16 }]}>
              <Text style={s.body}>•  </Text>
              <Text style={[s.body, s.flex]}>{inline(bullet[2])}</Text>
            </View>
          );
        }
        const num = /^\s*(\d+)[.)] (.*)$/.exec(line);
        if (num) {
          return (
            <View key={i} style={s.row}>
              <Text style={s.body}>{num[1]}.  </Text>
              <Text style={[s.body, s.flex]}>{inline(num[2])}</Text>
            </View>
          );
        }
        return <Text key={i} style={s.body}>{inline(line)}</Text>;
      })}
    </View>
  );
}

const INLINE = /\*\*(.+?)\*\*|\*([^*\s][^*]*?)\*/g;

function inline(str: string) {
  const parts: React.ReactNode[] = [];
  let last = 0;
  let m: RegExpExecArray | null;
  INLINE.lastIndex = 0;
  while ((m = INLINE.exec(str))) {
    if (m.index > last) parts.push(str.slice(last, m.index));
    parts.push(
      m[1] != null
        ? <Text key={m.index} style={s.bold}>{m[1]}</Text>
        : <Text key={m.index} style={s.italic}>{m[2]}</Text>,
    );
    last = m.index + m[0].length;
  }
  if (last < str.length) parts.push(str.slice(last));
  return parts;
}

const s = StyleSheet.create({
  body: { fontSize: 16, lineHeight: 23, color: colors.text },
  h1: { fontSize: 22, fontWeight: '700', color: colors.text, marginTop: 4 },
  h2: { fontSize: 19, fontWeight: '700', color: colors.text, marginTop: 4 },
  h3: { fontSize: 17, fontWeight: '600', color: colors.text, marginTop: 2 },
  bold: { fontWeight: '700' },
  italic: { fontStyle: 'italic' },
  row: { flexDirection: 'row' },
  flex: { flex: 1 },
});
