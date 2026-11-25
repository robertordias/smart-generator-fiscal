import { useState } from 'react';
import { Alert, Box, Button, Snackbar, MenuItem, Paper, Table, TableBody, TableCell, TableContainer, TableHead, TableRow, TextField, Typography } from '@mui/material';
import AddRounded from '@mui/icons-material/AddRounded';
import { useResource, useWorkspace } from '../context';
import { cnpj } from '../api';
import { EmptyState, ErrorBox, Loading, PageHeading } from '../components/Common';
import RecipientLinks from '../components/RecipientLinks';
import RecipientEditor from '../components/RecipientEditor';

export default function Recipients() {
  const { issuerId } = useWorkspace();
  return <RecipientList key={issuerId} />;
}
function RecipientList() {
  const { issuerId, setIssuerId, companies, isManager } = useWorkspace();
  const recipients = useResource(issuerId ? `/issuers/${issuerId}/recipients` : null);
  const [search, setSearch] = useState('');
  const [editing, setEditing] = useState(null);
  const [linking, setLinking] = useState(null);
  const [linked, setLinked] = useState(false);
  const rows = recipients.data?.filter(row => `${row.values.name} ${row.values.taxId} ${cnpj(row.values.taxId)}`.toLocaleLowerCase('pt-BR').includes(search.toLocaleLowerCase('pt-BR'))) || [];
  return <><PageHeading title="Tomadores" subtitle="Cadastre os clientes que recebem os serviços de cada emitente." action={<Box sx={{ display: 'flex', gap: 1, flexWrap: 'wrap' }}>{isManager && <Button variant="outlined" onClick={() => setLinking({})}>Vincular tomador existente</Button>}<Button variant="contained" startIcon={<AddRounded />} disabled={!issuerId} onClick={() => setEditing({})}>Cadastrar tomador</Button></Box>} />
    <Paper variant="outlined" className="filter-bar"><TextField select label="Empresa emitente" value={issuerId} onChange={e => setIssuerId(e.target.value)}><MenuItem value="">Selecione uma empresa</MenuItem>{companies.data?.map(company => <MenuItem key={company.id} value={company.id}>{company.name}</MenuItem>)}</TextField><TextField label="Buscar tomador" value={search} onChange={e => setSearch(e.target.value)} placeholder="Nome ou CPF/CNPJ" /></Paper>
    <ErrorBox error={companies.error || recipients.error} onRetry={() => { companies.reload(); recipients.reload(); }} />
    <Paper variant="outlined">{!issuerId ? <EmptyState title="Selecione o emitente" description="Os tomadores são cadastrados por empresa, respeitando os vínculos de acesso." /> : recipients.loading ? <Loading /> : !rows.length ? <EmptyState title={search ? 'Nenhum tomador encontrado' : 'Nenhum tomador cadastrado'} description="Cadastre um tomador para reutilizar seus dados na preparação da nota." /> : <TableContainer><Table aria-label="Tomadores cadastrados"><TableHead><TableRow><TableCell>Nome / razão social</TableCell><TableCell>CPF/CNPJ</TableCell><TableCell>E-mail</TableCell><TableCell /></TableRow></TableHead><TableBody>{rows.map(row => <TableRow key={row.id}><TableCell><Typography variant="body2" fontWeight={600}>{row.values.name}</Typography></TableCell><TableCell>{cnpj(row.values.taxId)}</TableCell><TableCell>{row.values.email || '—'}</TableCell><TableCell><Button size="small" onClick={() => setEditing(row)}>Editar tomador</Button>{isManager && <Button size="small" onClick={() => setLinking(row)}>Vincular prestadores</Button>}</TableCell></TableRow>)}</TableBody></Table></TableContainer>}</Paper>
    {linking && <RecipientLinks initialId={linking.id} onClose={() => setLinking(null)} onSaved={() => { setLinking(null); setLinked(true); recipients.reload(); }} />}
    <Snackbar open={linked} autoHideDuration={5000} onClose={() => setLinked(false)}><Alert severity="success">Tomador vinculado aos prestadores selecionados.</Alert></Snackbar>
    {editing && <RecipientEditor issuerId={issuerId} initial={editing} onClose={() => setEditing(null)} onSaved={() => { setEditing(null); recipients.reload(); }} />}
  </>;
}
