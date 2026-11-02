from pathlib import Path

p = Path('app/src/main/java/com/twoshare/app/ui/MessengerScreenV2.kt')
s = p.read_text()

old = 'if(selected==i)Icon(Icons.Default.CheckCircle,"Selected",tint=MaterialTheme.colorScheme.primary)}}}},confirmButton='
new = 'if(selected==i)Icon(Icons.Default.CheckCircle,"Selected",tint=MaterialTheme.colorScheme.primary)}}}}},confirmButton='

if old not in s:
    raise RuntimeError('Expected ThemePickerDialog brace anchor not found')

p.write_text(s.replace(old, new, 1))
