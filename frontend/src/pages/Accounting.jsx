import { useState } from 'react';
import { Alert, Button, MenuItem, Paper, Stack, TextField, Typography } from '@mui/material';
import FolderZipOutlined from '@mui/icons-material/FolderZipOutlined';
import { useAuth, useResource, useWorkspace } from '../context';
import { money, query, today } from '../api';
import { ErrorBox, FieldValue, Loading, PageHeading } from '../components/Common';

export default function Accounting() {
  const { issuerId } = useWorkspace();
  return <AccountingContent key={issuerId} />;
}
function AccountingContent() {
  const { api } = useAuth();
  const { issuerId, setIssuerId, companies, isManager } = useWorkspace();
  const [from, setFrom] = useState(`${today().slice(0, 7)}-01`);
  const [to, setTo] = useState(today());
  const [environment, setEnvironment] = useState('PRODUCAO');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState(null);
  const filters = { issuerId, from, to, environment };
  const valid = !!from && !!to && from <= to;
  const summary = useResource(isManager && valid ? `/office/xml/summary?${query(filters)}` : null);

  async function download() {
    setBusy(true); setError(null);
    try {
      const blob = await api(`/office/xml/export?${query(filters)}`, { responseType: 'blob' });
      const url = URL.createObjectURL(blob); const anchor = document.createElement('a');
      anchor.href = url; anchor.download = `xml-${environment.toLowerCase()}-${from}-${to}.zip`; anchor.click(); setTimeout(() => URL.revokeObjectURL(url), 1000);
    } catch (e) { setError(e); } finally { setBusy(false); }
  }

  return <><PageHeading title="Contábil · XML" subtitle="Feche o período e baixe os XMLs oficiais de toda a carteira em um único pacote." />
    {!isManager ? <Alert severity="info">A exportação contábil em lote é exclusiva do gestor do escritório.</Alert> : <>
      <Paper variant="outlined" className="filter-bar"><TextField select slotProps={{ inputLabel: { shrink: true }, select: { displayEmpty: true } }} label="Emitente da exportação" value={issuerId} onChange={e => setIssuerId(e.target.value)}><MenuItem value="">Todas as empresas</MenuItem>{companies.data?.map(company => <MenuItem key={company.id} value={company.id}>{company.name}</MenuItem>)}</TextField>
        <TextField select label="Ambiente da exportação" value={environment} onChange={e => setEnvironment(e.target.value)}><MenuItem value="PRODUCAO">Produção</MenuItem><MenuItem value="HOMOLOGACAO">Homologação</MenuItem></TextField>
        <TextField required label="Competência inicial" type="date" value={from} onChange={e => setFrom(e.target.value)} slotProps={{ inputLabel: { shrink: true } }} />
        <TextField required label="Competência final" type="date" value={to} onChange={e => setTo(e.target.value)} slotProps={{ inputLabel: { shrink: true } }} /></Paper>
      {!valid && <Alert severity="warning" sx={{ mb: 2 }}>Informe um período válido de competência.</Alert>}
      <ErrorBox error={error || summary.error || companies.error} onRetry={summary.reload} />
      {environment === 'HOMOLOGACAO' && <Alert severity="info" sx={{ mb: 2 }}>Os documentos deste pacote são de testes e não possuem validade fiscal.</Alert>}
      <Paper variant="outlined" sx={{ p: 3 }}><Typography variant="h6" sx={{ mb: 3 }}>Documentos do período</Typography>
        {summary.loading ? <Loading /> : <div className="field-grid"><FieldValue label="NFS-e com XML oficial">{summary.data?.documents ?? '—'}</FieldValue><FieldValue label="Notas canceladas incluídas">{summary.data?.cancelled ?? '—'}</FieldValue><FieldValue label="Valor dos documentos (inclui cancelados)">{summary.data ? money(summary.data.documentAmount) : '—'}</FieldValue><FieldValue label="Limite por pacote">500 documentos · 50 MB de XML</FieldValue></div>}
        <Typography variant="body2" color="text.secondary" sx={{ my: 3 }}>O ZIP organiza os arquivos por CNPJ e competência, inclui eventos de cancelamento disponíveis e um manifesto com situação e checksum. Rascunhos, DPS sem autorização e certificados A1 não entram no pacote.</Typography>
        {summary.data?.documents > summary.data?.limit && <Alert severity="warning" sx={{ mb: 2 }}>Reduza o período ou selecione uma empresa para baixar até 500 documentos.</Alert>}
        <Stack direction="row" gap={2} flexWrap="wrap"><Button startIcon={<FolderZipOutlined />} variant="contained" onClick={download} disabled={busy || summary.loading || !valid || !summary.data?.documents || summary.data.documents > summary.data.limit}>{busy ? 'Gerando pacote…' : 'Baixar XMLs em ZIP'}</Button><Button onClick={summary.reload} disabled={busy || summary.loading || !valid}>Atualizar resumo</Button></Stack>
      </Paper>
    </>}
  </>;
}
