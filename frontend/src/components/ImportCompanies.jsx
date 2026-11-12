import { useState } from 'react';
import { Alert, Box, Button, Chip, Dialog, DialogActions, DialogContent, DialogTitle, Pagination, Stack, Table, TableBody, TableCell, TableContainer, TableHead, TableRow, Typography } from '@mui/material';
import UploadFileRounded from '@mui/icons-material/UploadFileRounded';
import EditOutlined from '@mui/icons-material/EditOutlined';
import { useAuth } from '../context';
import { cnpj } from '../api';
import { ErrorBox } from './Common';
import CorrectImportRow from './CorrectImportRow';

const labels = { READY: 'Pronta', IMPORTED: 'Importada', SKIPPED: 'Ignorada', INVALID: 'Corrigir' };
const colors = { READY: 'success', IMPORTED: 'success', SKIPPED: 'default', INVALID: 'error' };

export default function ImportCompanies({ onClose, onImported }) {
  const { api } = useAuth();
  const [file, setFile] = useState(null);
  const [report, setReport] = useState(null);
  const [error, setError] = useState(null);
  const [busy, setBusy] = useState(false);
  const [saved, setSaved] = useState(false);
  const [page, setPage] = useState(1);
  const [corrections, setCorrections] = useState({});
  const [editing, setEditing] = useState(null);
  const [correctionError, setCorrectionError] = useState(null);
  const [importedCount, setImportedCount] = useState(0);
  function upload(selected, changes) {
    const body = new FormData(); body.append('file', selected); body.append('corrections', JSON.stringify(changes));
    return body;
  }
  async function preview(event) {
    const selected = event.target.files?.[0];
    event.target.value = '';
    if (!selected) return;
    setFile(null); setReport(null); setSaved(false); setError(null); setPage(1); setCorrections({}); setEditing(null); setImportedCount(0);
    if (selected.size > 1024 * 1024) { setError(new Error('Selecione um CSV de até 1 MB.')); return; }
    setBusy(true);
    try {
      const result = await api('/issuers/import/preview', { method: 'POST', body: upload(selected, {}) });
      setFile(selected); setReport(result);
    } catch (e) { setError(e); } finally { setBusy(false); }
  }
  async function correct(values) {
    if (!editing || busy) return;
    const next = { ...corrections, [editing.line]: { ...corrections[editing.line], ...values } };
    setBusy(true); setCorrectionError(null); setError(null);
    try {
      const result = await api('/issuers/import/preview', { method: 'POST', body: upload(file, next) });
      setCorrections(next); setReport(result); setSaved(false);
      const row = result.rows.find(item => item.line === editing.line);
      if (row?.status === 'INVALID') {
        setEditing(row); setCorrectionError(new Error('Ainda há campos inválidos. Confira os valores indicados.'));
      } else setEditing(null);
    } catch (e) { setCorrectionError(e); } finally { setBusy(false); }
  }
  async function save() {
    if (!file || saved || busy) return;
    setError(null); setBusy(true);
    try {
      const result = await api('/issuers/import', { method: 'POST', body: upload(file, corrections) });
      setReport(result); setImportedCount(count => count + result.imported);
      setSaved(true); setPage(1); onImported();
    } catch (e) { setError(e); } finally { setBusy(false); }
  }
  return <><Dialog open fullWidth maxWidth="lg" onClose={() => !busy && !editing && onClose()}>
    <DialogTitle>Importar empresas por CSV</DialogTitle>
    <DialogContent>
      <Typography color="text.secondary" variant="body2" sx={{ mb: 2 }}>Use a relação de empresas com as 33 colunas na ordem original, cabeçalho e separador ponto e vírgula. Até 1 MB e 5.000 empresas; UTF-8 ou Windows-1252.</Typography>
      <Alert severity="info" sx={{ mb: 2 }}>Serão gravados identificação, inscrições, CNAE, endereço e contatos. CNPJs já cadastrados serão ignorados. Os padrões fiscais existentes serão preservados.</Alert>
      <Button component="label" variant="outlined" startIcon={<UploadFileRounded />} disabled={busy}>Selecionar CSV<input aria-label="Arquivo CSV de empresas" type="file" accept=".csv,text/csv" hidden onChange={preview} /></Button>
      {file && <Typography variant="body2" sx={{ mt: 1 }}>{file.name}</Typography>}
      {busy && <Typography role="status" sx={{ mt: 2 }}>Processando arquivo…</Typography>}
      <Box sx={{ mt: 2 }}><ErrorBox error={error} /></Box>
      {report && <>
        {importedCount > 0 && <Alert severity="success" sx={{ mb: 2 }}>Importação concluída: {importedCount} empresas gravadas na base.</Alert>}
        <Stack direction="row" gap={1} flexWrap="wrap" sx={{ mb: 2 }}>
          <Chip label={`${report.total} linhas`} /><Chip color="success" variant="outlined" label={`${saved ? report.imported : report.ready} ${saved ? 'importadas' : 'prontas'}`} />
          <Chip label={`${report.skipped} ignoradas`} /><Chip color={report.invalid ? 'error' : 'default'} variant="outlined" label={`${report.invalid} com erro`} />
        </Stack>
        <TableContainer sx={{ maxHeight: 360 }}><Table size="small" stickyHeader aria-label="Prévia da importação"><TableHead><TableRow>
          {['Linha', 'Empresa / CNPJ', 'Cidade / IBGE', 'CNAE', 'Situação', 'Ação'].map(label => <TableCell key={label}>{label}</TableCell>)}
        </TableRow></TableHead><TableBody>{report.rows.slice((page - 1) * 50, page * 50).map(row => <TableRow key={row.line}>
          <TableCell>{row.line}</TableCell><TableCell sx={{ minWidth: 190 }}><Typography variant="body2" fontWeight={600}>{row.name || '—'}</Typography>{cnpj(row.cnpj)}</TableCell>
          <TableCell>{[row.registration?.city, row.registration?.uf].filter(Boolean).join(' / ')}<Typography variant="caption" display="block">{row.municipality || 'Não identificado'}</Typography></TableCell>
          <TableCell>{row.registration?.cnae || '—'}</TableCell><TableCell sx={{ minWidth: 220 }}><Chip size="small" color={colors[row.status]} label={labels[row.status]} />{row.messages.map((message, index) => <Typography key={index} variant="caption" display="block" sx={{ mt: .5 }}>{message}</Typography>)}</TableCell>
          <TableCell>{row.status === 'INVALID' && <Button size="small" startIcon={<EditOutlined />} disabled={busy} onClick={() => { setCorrectionError(null); setEditing(row); }}>Corrigir</Button>}</TableCell>
        </TableRow>)}</TableBody></Table></TableContainer>
        {report.rows.length > 50 && <Pagination count={Math.ceil(report.rows.length / 50)} page={page} onChange={(_, value) => setPage(value)} sx={{ mt: 2 }} />}
        <Typography variant="body2" color="text.secondary" sx={{ mt: 2 }}>Somente linhas prontas serão importadas. Use Corrigir para ajustar os erros aqui na aplicação, inclusive após importar as demais linhas. A tributação do arquivo é uma referência; revise enquadramento, códigos de serviço e alíquotas antes de emitir notas.</Typography>
      </>}
    </DialogContent>
    <DialogActions sx={{ p: 3 }}><Button color="secondary" onClick={onClose} disabled={busy}>{saved ? 'Concluir' : 'Cancelar'}</Button><Button variant="contained" onClick={save} disabled={busy || saved || !report?.ready}>Importar {report?.ready || ''} empresas</Button></DialogActions>
  </Dialog>{editing && <CorrectImportRow row={editing} busy={busy} error={correctionError} onClose={() => setEditing(null)} onSave={correct} />}</>;
}
