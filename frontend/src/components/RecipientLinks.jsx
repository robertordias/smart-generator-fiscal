import { useState } from 'react';
import { Alert, Button, Checkbox, Dialog, DialogActions, DialogContent, DialogTitle, FormControlLabel, MenuItem, TextField, Typography } from '@mui/material';
import { useAuth, useResource, useWorkspace } from '../context';
import { cnpj } from '../api';
import { ErrorBox, Loading } from './Common';

export default function RecipientLinks({ initialId = '', onClose, onSaved }) {
  const { api } = useAuth();
  const { companies } = useWorkspace();
  const catalog = useResource('/recipients');
  const [recipientId, setRecipientId] = useState(initialId);
  const [selected, setSelected] = useState([]);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState(null);
  const recipient = catalog.data?.find(row => row.id === recipientId);
  async function save(event) {
    event.preventDefault(); setBusy(true); setError(null);
    try {
      await api(`/recipients/${recipientId}/issuers`, { method: 'POST', body: { issuerIds: selected } });
      onSaved();
    } catch (e) { setError(e); } finally { setBusy(false); }
  }
  return <Dialog open fullWidth maxWidth="sm" onClose={() => !busy && onClose()}><form onSubmit={save}>
    <DialogTitle>Vincular tomador a prestadores</DialogTitle><DialogContent>
      <Alert severity="info" sx={{ mb: 2 }}>O mesmo tomador ficará disponível para os prestadores selecionados. Edições do cadastro serão compartilhadas; notas e rascunhos existentes mantêm os dados originais.</Alert>
      <ErrorBox error={error || catalog.error || companies.error} onRetry={catalog.reload} />
      {catalog.loading ? <Loading /> : <TextField select required label="Tomador existente" value={recipientId} disabled={busy} onChange={e => { setRecipientId(e.target.value); setSelected([]); }} helperText="Cadastros anteriores com o mesmo CPF/CNPJ permanecem separados para revisão.">{catalog.data?.map(row => <MenuItem key={row.id} value={row.id}>{row.values.name} · {cnpj(row.values.taxId)} · {companies.data?.find(c => c.id === row.issuerIds[0])?.name}</MenuItem>)}</TextField>}
      {!catalog.loading && !catalog.data?.length && <Typography sx={{ mt: 2 }}>Cadastre primeiro um tomador em uma empresa emitente.</Typography>}
      {recipient && <><Typography variant="subtitle2" sx={{ mt: 3, mb: 1 }}>Prestadores / empresas emitentes</Typography>{companies.data?.map(company => {
        const linked = recipient.issuerIds.includes(company.id);
        return <FormControlLabel key={company.id} sx={{ display: 'flex' }} label={`${company.name}${linked ? ' — já vinculado' : ''}`} control={<Checkbox disabled={busy || linked} checked={linked || selected.includes(company.id)} onChange={e => setSelected(current => e.target.checked ? [...current, company.id] : current.filter(id => id !== company.id))} />} />;
      })}</>}
    </DialogContent><DialogActions sx={{ p: 3 }}><Button color="secondary" disabled={busy} onClick={onClose}>Cancelar</Button><Button type="submit" variant="contained" disabled={busy || !recipient || !selected.length}>{busy ? 'Vinculando…' : 'Salvar vínculos'}</Button></DialogActions>
  </form></Dialog>;
}
