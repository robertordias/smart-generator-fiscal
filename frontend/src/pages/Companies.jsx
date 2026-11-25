import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { Alert, Avatar, Button, Dialog, DialogActions, DialogContent, DialogTitle, InputAdornment, Paper, Snackbar, Table, TableBody, TableCell, TableContainer, TableHead, TableRow, TextField, Typography } from '@mui/material';
import UploadFileRounded from '@mui/icons-material/UploadFileRounded';
import ImportCompanies from '../components/ImportCompanies';
import CompanyRegistration from '../components/CompanyRegistration';
import AddRounded from '@mui/icons-material/AddRounded';
import SearchRounded from '@mui/icons-material/SearchRounded';
import BusinessOutlined from '@mui/icons-material/BusinessOutlined';
import ArrowForwardRounded from '@mui/icons-material/ArrowForwardRounded';
import { useAuth, useWorkspace } from '../context';
import { cnpj } from '../api';
import { EmptyState, ErrorBox, Loading, PageHeading } from '../components/Common';

export default function Companies() {
  const { api } = useAuth();
  const { companies, isManager, setIssuerId } = useWorkspace();
  const navigate = useNavigate();
  const [importOpen, setImportOpen] = useState(false);
  const [detail, setDetail] = useState(null);
  const [search, setSearch] = useState('');
  const [open, setOpen] = useState(false);
  const [form, setForm] = useState({ name: '', cnpj: '', municipality: '' });
  const [error, setError] = useState(null);
  const [busy, setBusy] = useState(false);
  const [success, setSuccess] = useState(false);
  const items = companies.data?.filter(c => `${c.name} ${c.cnpj} ${cnpj(c.cnpj)}`.toLocaleLowerCase('pt-BR').includes(search.toLocaleLowerCase('pt-BR'))) || [];

  async function save(event) {
    event.preventDefault(); setError(null); setBusy(true);
    try {
      const company = await api('/issuers', { method: 'POST', body: { ...form, name: form.name.trim() } });
      companies.reload(); setIssuerId(company.id); setOpen(false); setSuccess(true); setForm({ name: '', cnpj: '', municipality: '' });
    } catch (e) { setError(e); } finally { setBusy(false); }
  }

  function configure(company) { setIssuerId(company.id); navigate('/configuracoes-fiscais'); }
  return <><PageHeading title="Empresas" subtitle="Sua carteira de emitentes, organizada em um só lugar." action={isManager && <div style={{ display: 'flex', gap: 12, flexWrap: 'wrap' }}><Button variant="outlined" startIcon={<UploadFileRounded />} onClick={() => setImportOpen(true)}>Importar CSV</Button><Button variant="contained" startIcon={<AddRounded />} onClick={() => { setError(null); setOpen(true); }}>Cadastrar empresa</Button></div>} /><ErrorBox error={companies.error} onRetry={companies.reload} /><Paper variant="outlined"><div className="panel-header"><div><Typography variant="h6">Carteira de empresas</Typography><Typography variant="body2" color="text.secondary">{companies.data?.length || 0} empresas vinculadas ao seu acesso</Typography></div><TextField label="Buscar empresa" placeholder="Nome ou CNPJ" value={search} onChange={e => setSearch(e.target.value)} sx={{ maxWidth: 310 }} slotProps={{ input: { startAdornment: <InputAdornment position="start"><SearchRounded sx={{ fontSize: 19 }} /></InputAdornment> } }} /></div>{companies.loading ? <Loading /> : !items.length ? <EmptyState icon={<BusinessOutlined />} title={search ? 'Nenhuma empresa encontrada' : 'Sua carteira começa aqui'} description={search ? 'Experimente buscar por outro nome ou CNPJ.' : isManager ? 'Cadastre o primeiro emitente e personalize seus padrões fiscais.' : 'Peça ao gestor do escritório para vincular sua empresa.'} action={!search && isManager && <Button variant="outlined" startIcon={<AddRounded />} onClick={() => setOpen(true)}>Cadastrar primeira empresa</Button>} /> : <TableContainer><Table aria-label="Empresas cadastradas"><TableHead><TableRow><TableCell>Empresa</TableCell><TableCell>CNPJ</TableCell><TableCell>Município IBGE</TableCell><TableCell align="right">Configuração</TableCell></TableRow></TableHead><TableBody>{items.map(company => <TableRow key={company.id} hover><TableCell><div className="company-cell"><Avatar variant="rounded" sx={{ bgcolor: '#f4f0e7', color: '#9b7c3d', width: 37, height: 37, fontSize: '.75rem', fontWeight: 700 }}>{company.name.slice(0, 2).toUpperCase()}</Avatar><Typography variant="body2" fontWeight={600}>{company.name}</Typography></div></TableCell><TableCell sx={{ whiteSpace: 'nowrap' }}>{cnpj(company.cnpj)}</TableCell><TableCell>{company.municipality}</TableCell><TableCell align="right"><Button size="small" onClick={() => setDetail(company)}>Dados cadastrais</Button><Button size="small" endIcon={<ArrowForwardRounded />} onClick={() => configure(company)}>Padrões fiscais</Button></TableCell></TableRow>)}</TableBody></Table></TableContainer>}</Paper><Typography variant="body2" color="text.secondary" sx={{ mt: 2 }}>Cada empresa possui sua própria configuração fiscal e histórico de documentos.</Typography>
    {importOpen && <ImportCompanies onClose={() => setImportOpen(false)} onImported={companies.reload} />}
    {detail && <CompanyRegistration company={detail} onClose={() => setDetail(null)} />}
    <Dialog open={open} onClose={() => !busy && setOpen(false)} fullWidth maxWidth="sm"><form onSubmit={save}><DialogTitle>Cadastrar empresa</DialogTitle><DialogContent><Typography variant="body2" color="text.secondary" sx={{ mb: 3 }}>Informe os dados do emitente. Os padrões fiscais serão configurados na próxima etapa.</Typography><ErrorBox error={error} /><div className="form-stack"><TextField autoFocus required label="Razão social" value={form.name} onChange={e => setForm({ ...form, name: e.target.value })} slotProps={{ htmlInput: { maxLength: 160 } }} disabled={busy} /><TextField required label="CNPJ" value={form.cnpj} onChange={e => setForm({ ...form, cnpj: e.target.value.replace(/\D/g, '').slice(0, 14) })} helperText="Informe os 14 dígitos, sem pontuação." slotProps={{ htmlInput: { pattern: '[0-9]{14}', inputMode: 'numeric' } }} disabled={busy} /><TextField required label="Código do município IBGE" value={form.municipality} onChange={e => setForm({ ...form, municipality: e.target.value.replace(/\D/g, '').slice(0, 7) })} helperText="Código de 7 dígitos. Exemplo: 3550308 para São Paulo." slotProps={{ htmlInput: { pattern: '[0-9]{7}', inputMode: 'numeric' } }} disabled={busy} /></div></DialogContent><DialogActions sx={{ p: 3 }}><Button color="secondary" onClick={() => setOpen(false)} disabled={busy}>Cancelar</Button><Button variant="contained" type="submit" disabled={busy}>{busy ? 'Salvando…' : 'Salvar empresa'}</Button></DialogActions></form></Dialog><Snackbar open={success} autoHideDuration={4500} onClose={() => setSuccess(false)}><Alert severity="success" onClose={() => setSuccess(false)}>Empresa cadastrada com sucesso.</Alert></Snackbar></>;
}
