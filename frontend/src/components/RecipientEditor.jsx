import { useState } from 'react';
import { Button, Checkbox, Dialog, DialogActions, DialogContent, DialogTitle, FormControlLabel, TextField, Typography } from '@mui/material';
import { useAuth } from '../context';
import { ErrorBox } from './Common';

const blankAddress = () => ({ municipality: '', zip: '', street: '', number: '', neighborhood: '' });
export default function RecipientEditor({ issuerId, initial, onClose, onSaved }) {
  const { api } = useAuth();
  const [form, setForm] = useState(() => ({ taxId: '', name: '', email: '', address: null, ...initial?.values }));
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState(null);
  const change = (key, value) => setForm(f => ({ ...f, [key]: value }));
  async function save(event) {
    event.preventDefault(); setBusy(true); setError(null);
    try {
      const saved = await api(`/issuers/${issuerId}/recipients${initial?.id ? `/${initial.id}` : ''}`, {
        method: initial?.id ? 'PUT' : 'POST', body: { ...form, name: form.name.trim(), email: form.email?.trim() || null },
      });
      onSaved(saved);
    } catch (e) { setError(e); } finally { setBusy(false); }
  }
  return <Dialog open fullWidth maxWidth="md" onClose={() => !busy && onClose()}><form onSubmit={save}>
    <DialogTitle>{initial?.id ? 'Editar tomador' : 'Cadastrar tomador'}</DialogTitle><DialogContent>
      <Typography color="text.secondary" variant="body2" sx={{ mb: 3 }}>Dados vinculados à empresa emitente para reutilização nas notas fiscais.</Typography><ErrorBox error={error} />
      <div className="field-grid"><TextField autoFocus required label="CPF/CNPJ do tomador" value={form.taxId} onChange={e => change('taxId', e.target.value.replace(/\D/g, '').slice(0, 14))} slotProps={{ htmlInput: { pattern: '[0-9]{11}|[0-9]{14}', inputMode: 'numeric' } }} disabled={busy} />
        <TextField required label="Nome / razão social do tomador" value={form.name} onChange={e => change('name', e.target.value)} slotProps={{ htmlInput: { maxLength: 150 } }} disabled={busy} />
        <TextField label="E-mail do tomador" type="email" value={form.email || ''} onChange={e => change('email', e.target.value)} slotProps={{ htmlInput: { maxLength: 80 } }} disabled={busy} /></div>
      <FormControlLabel sx={{ my: 2 }} label="Informar endereço do tomador" control={<Checkbox checked={!!form.address} disabled={busy} onChange={e => change('address', e.target.checked ? blankAddress() : null)} />} />
      {form.address && <div className="field-grid">{[['municipality', 'Município IBGE', '[0-9]{7}', 7], ['zip', 'CEP', '[0-9]{8}', 8], ['street', 'Logradouro', undefined, 255], ['number', 'Número', undefined, 60], ['neighborhood', 'Bairro', undefined, 60]].map(([key, label, pattern, maxLength]) => <TextField required disabled={busy} key={key} label={label} value={form.address[key]} onChange={e => change('address', { ...form.address, [key]: pattern ? e.target.value.replace(/\D/g, '') : e.target.value })} slotProps={{ htmlInput: { pattern, maxLength } }} />)}</div>}
    </DialogContent><DialogActions sx={{ p: 3 }}><Button color="secondary" disabled={busy} onClick={onClose}>Cancelar</Button><Button type="submit" variant="contained" disabled={busy}>{busy ? 'Salvando…' : 'Salvar tomador'}</Button></DialogActions>
  </form></Dialog>;
}
