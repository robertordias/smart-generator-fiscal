import { useEffect, useMemo, useState } from 'react';
import { Alert, Button, Dialog, DialogActions, DialogContent, DialogTitle, TextField, Typography } from '@mui/material';
import { ErrorBox } from './Common';

const labels = {
  name: 'Razão social', cnpj: 'CNPJ', city: 'Cidade', uf: 'UF', cnae: 'CNAE', zip: 'CEP', email: 'E-mail',
  tradeName: 'Nome fantasia', stateRegistration: 'Inscrição estadual', municipalRegistration: 'Inscrição municipal',
  taxationReference: 'Tributação informada', street: 'Logradouro', number: 'Número', complement: 'Complemento',
  neighborhood: 'Bairro', phone: 'Telefone', layout: 'Conteúdo da linha CSV',
};

export default function CorrectImportRow({ row, busy, error, onClose, onSave }) {
  const [form, setForm] = useState({});
  const fieldErrors = useMemo(() => {
    const fields = row.fieldErrors;
    if (!fields || typeof fields !== 'object' || Array.isArray(fields)) return {};
    return Object.fromEntries(Object.entries(fields).filter(([, message]) => typeof message === 'string'));
  }, [row.fieldErrors]);
  const canCorrect = Object.keys(fieldErrors).length > 0;
  useEffect(() => {
    const values = { ...row.registration, name: row.name, cnpj: row.cnpj, layout: row.rawLine };
    setForm(Object.fromEntries(Object.keys(fieldErrors).map(key => [key, values[key] || ''])));
  }, [row, fieldErrors]);
  function submit(event) { event.preventDefault(); if (canCorrect && !busy) onSave(form); }
  return <Dialog open fullWidth maxWidth="sm" onClose={() => !busy && onClose()}>
    <form onSubmit={submit}>
      <DialogTitle>Corrigir linha {row.line}</DialogTitle>
      <DialogContent>
        <Typography variant="body2" color="text.secondary" sx={{ mb: 2 }}>{row.name || 'Registro com erro'} · Ajuste os campos indicados. Os dados serão validados novamente antes da importação.</Typography>
        <ErrorBox error={error} />
        {!canCorrect && <Alert severity="warning" sx={{ mb: 2 }}>O servidor não informou os campos para correção. Reinicie o backend com a versão atualizada e selecione o CSV novamente. Você pode cancelar e voltar à prévia.</Alert>}
        {fieldErrors.layout && <Alert severity="info" sx={{ mb: 2 }}>A linha precisa ter 33 colunas na ordem original, separadas por ponto e vírgula. Mantenha entre aspas os valores que contêm ponto e vírgula.</Alert>}
        <div className="form-stack">{Object.entries(fieldErrors).map(([key, message], index) => <TextField
          key={key} autoFocus={index === 0} label={labels[key] || key} value={form[key] ?? ''} disabled={busy}
          onChange={event => setForm(current => ({ ...current, [key]: key === 'uf' ? event.target.value.toUpperCase() : event.target.value }))}
          helperText={message} multiline={key === 'layout'} minRows={key === 'layout' ? 4 : undefined}
        />)}</div>
        <Typography variant="caption" color="text.secondary" display="block" sx={{ mt: 2 }}>Salvar correção atualiza a prévia. A gravação na base ocorre ao confirmar a importação.</Typography>
      </DialogContent>
      <DialogActions sx={{ p: 3 }}><Button color="secondary" onClick={onClose} disabled={busy}>Cancelar correção</Button><Button type="submit" variant="contained" disabled={busy || !canCorrect}>{busy ? 'Validando…' : 'Salvar correção'}</Button></DialogActions>
    </form>
  </Dialog>;
}
