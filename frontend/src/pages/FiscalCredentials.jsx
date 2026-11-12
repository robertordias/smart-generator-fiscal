import { useEffect, useState } from 'react';
import { Alert, Box, Button, Checkbox, Chip, Dialog, DialogActions, DialogContent, DialogTitle, FormControlLabel, MenuItem, Paper, Snackbar, Table, TableBody, TableCell, TableContainer, TableHead, TableRow, TextField, Typography } from '@mui/material';
import AddRounded from '@mui/icons-material/AddRounded';
import VerifiedUserOutlined from '@mui/icons-material/VerifiedUserOutlined';
import { useAuth, useResource, useWorkspace } from '../context';
import { date, today } from '../api';
import { EmptyState, ErrorBox, Loading, PageHeading } from '../components/Common';

const initialProfile = { simplesOption: '3', simplesAssessment: '1', specialRegime: '0', municipalRegistration: '', series: '1', lastDpsNumber: 0 };
const initialProxy = () => ({ certificateId: '', reference: '', validFrom: today(), validTo: '', canIssue: true, canQuery: true, canCancel: true, active: true });

export default function FiscalCredentials() {
  const { issuerId } = useWorkspace();
  return <Credentials key={issuerId} />;
}
function Credentials() {
  const { api } = useAuth();
  const { issuerId, setIssuerId, companies, isManager } = useWorkspace();
  const config = useResource('/nfse/config');
  const certificates = useResource(isManager ? '/nfse/certificates' : null);
  const storedProfile = useResource(issuerId ? `/nfse/issuers/${issuerId}/profile` : null);
  const registration = useResource(issuerId ? `/issuers/${issuerId}/registration` : null);
  const storedProxy = useResource(issuerId ? `/nfse/issuers/${issuerId}/proxy` : null);
  const [profile, setProfile] = useState(initialProfile);
  const [proxy, setProxy] = useState(initialProxy);
  const [open, setOpen] = useState(false);
  const [certificate, setCertificate] = useState({ label: '', holderTaxId: '', password: '' });
  const [file, setFile] = useState(null);
  const [busy, setBusy] = useState('');
  const [error, setError] = useState(null);
  const [notice, setNotice] = useState('');
  useEffect(() => { if (storedProfile.data) setProfile(storedProfile.data); }, [storedProfile.data]);
  useEffect(() => {
    if (storedProxy.data) {
      const { certificateId, reference, validFrom, validTo, canIssue, canQuery, canCancel, active } = storedProxy.data;
      setProxy({ certificateId, reference, validFrom, validTo, canIssue, canQuery, canCancel, active });
    }
  }, [storedProxy.data]);
  async function importCertificate(event) {
    event.preventDefault(); setError(null);
    if (!file) { setError(new Error('Selecione o arquivo A1 (.pfx ou .p12).')); return; }
    if (file.size > 1024 * 1024) { setError(new Error('O arquivo excede 1 MB.')); return; }
    setBusy('certificate');
    try {
      const bytes = new Uint8Array(await file.arrayBuffer()); let binary = '';
      for (let offset = 0; offset < bytes.length; offset += 8192) binary += String.fromCharCode(...bytes.subarray(offset, offset + 8192));
      await api('/nfse/certificates', { method: 'POST', body: { ...certificate, pfxBase64: btoa(binary) } });
      setCertificate({ label: '', holderTaxId: '', password: '' }); setFile(null); setOpen(false); certificates.reload(); setNotice('A1 validado e armazenado com proteção criptográfica.');
    } catch (e) { setError(e); } finally { setBusy(''); }
  }
  async function save(event, kind) {
    event.preventDefault(); setBusy(kind); setError(null);
    try {
      await api(`/nfse/issuers/${issuerId}/${kind}`, { method: 'PUT', body: kind === 'profile' ? { ...profile, lastDpsNumber: Number(profile.lastDpsNumber) } : proxy });
      (kind === 'profile' ? storedProfile : storedProxy).reload(); setNotice(kind === 'profile' ? 'Enquadramento e série salvos.' : 'Procuração vinculada. A aceitação dos poderes será validada pelo Fisco.');
    } catch (e) { setError(e); } finally { setBusy(''); }
  }
  async function revoke(id) {
    if (!window.confirm('Revogar este A1 no sistema? Novas assinaturas com ele serão bloqueadas.')) return;
    setBusy(id); setError(null);
    try { await api(`/nfse/certificates/${id}`, { method: 'DELETE' }); certificates.reload(); setNotice('Certificado revogado no sistema.'); }
    catch (e) { setError(e); } finally { setBusy(''); }
  }
  const change = (key, value) => setProfile(p => ({ ...p, [key]: value }));
  return <><PageHeading title="Certificados e procurações" subtitle="O A1 do contador e os poderes para atuar em nome de cada cliente." action={isManager && <Button variant="contained" startIcon={<AddRounded />} onClick={() => { setError(null); setOpen(true); }}>Cadastrar A1</Button>} />
    <ErrorBox error={error || config.error || certificates.error} />
    {config.data && <Alert severity={config.data.environment === 'PRODUCAO' ? 'warning' : 'info'} sx={{ mb: 3 }}>Ambiente: <strong>{config.data.environment === 'PRODUCAO' ? 'Produção' : 'Homologação (testes)'}</strong>. {!config.data.vaultConfigured || !config.data.trustConfigured ? 'O administrador precisa configurar o cofre e as âncoras ICP-Brasil no servidor antes de importar o A1.' : 'O certificado será validado antes de cada assinatura e comunicação.'}</Alert>}
    {!isManager ? <Alert severity="info">O cadastro de certificados, procurações e enquadramentos é administrado pelo contador.</Alert> : <>
      <Paper variant="outlined" sx={{ mb: 3 }}><div className="panel-header"><Typography variant="h6">A1 do escritório / representante</Typography><Chip label={`${certificates.data?.length || 0} certificados`} size="small" /></div>{certificates.loading ? <Loading /> : !certificates.data?.length ? <EmptyState icon={<VerifiedUserOutlined />} title="Cadastre o certificado do contador" description="O mesmo A1 pode atender vários clientes, desde que cada um tenha procuração válida para os serviços da NFS-e." /> : <TableContainer><Table aria-label="Certificados A1"><TableHead><TableRow><TableCell>Identificação</TableCell><TableCell>CPF/CNPJ titular</TableCell><TableCell>Validade</TableCell><TableCell>Situação</TableCell><TableCell /></TableRow></TableHead><TableBody>{certificates.data.map(c => <TableRow key={c.id}><TableCell>{c.label}</TableCell><TableCell>{c.holderTaxId}</TableCell><TableCell>{date(c.expiresAt.slice(0, 10))}</TableCell><TableCell><Chip size="small" label={c.status === 'ACTIVE' ? 'Cadastrado' : 'Revogado'} /></TableCell><TableCell>{c.status === 'ACTIVE' && <Button color="error" size="small" disabled={!!busy} onClick={() => revoke(c.id)}>Revogar</Button>}</TableCell></TableRow>)}</TableBody></Table></TableContainer>}</Paper>
      <Paper variant="outlined" className="filter-bar"><TextField select label="Empresa cliente" value={issuerId} onChange={e => setIssuerId(e.target.value)}><MenuItem value="">Selecione uma empresa</MenuItem>{companies.data?.map(c => <MenuItem key={c.id} value={c.id}>{c.name}</MenuItem>)}</TextField></Paper>
      {issuerId && <><ErrorBox error={storedProfile.error?.status === 422 ? null : storedProfile.error} /><ErrorBox error={storedProxy.error?.status === 422 ? null : storedProxy.error} /><div className="field-grid" style={{ alignItems: 'start' }}>
        <Paper component="form" onSubmit={e => save(e, 'profile')} variant="outlined" sx={{ p: 3 }}><Typography variant="h6" sx={{ mb: 3 }}>Enquadramento e numeração</Typography><div className="form-stack"><TextField select label="Situação no Simples Nacional" value={profile.simplesOption} onChange={e => change('simplesOption', e.target.value)}><MenuItem value="1">Não optante</MenuItem><MenuItem value="2">MEI</MenuItem><MenuItem value="3">ME/EPP optante</MenuItem></TextField>{profile.simplesOption === '3' && <TextField select label="Regime de apuração" value={profile.simplesAssessment} onChange={e => change('simplesAssessment', e.target.value)}><MenuItem value="1">Federais e ISS pelo Simples</MenuItem><MenuItem value="2">Federais pelo Simples; ISS fora</MenuItem><MenuItem value="3">Federais e ISS fora do Simples</MenuItem></TextField>}<TextField select label="Regime especial" value={profile.specialRegime} onChange={e => change('specialRegime', e.target.value)}>{[['0', 'Nenhum'], ['1', 'Ato cooperado'], ['2', 'Estimativa'], ['3', 'Microempresa municipal'], ['4', 'Notário / Registrador'], ['5', 'Profissional autônomo'], ['6', 'Sociedade de profissionais'], ['9', 'Outros']].map(([v, label]) => <MenuItem key={v} value={v}>{label}</MenuItem>)}</TextField>{registration.data?.municipalRegistration && <Button variant="outlined" disabled={!!busy || registration.data.municipalRegistration.length > 15} onClick={() => change('municipalRegistration', registration.data.municipalRegistration)}>Usar inscrição municipal importada</Button>}<TextField label="Inscrição municipal (quando exigida)" value={profile.municipalRegistration || ''} onChange={e => change('municipalRegistration', e.target.value)} slotProps={{ htmlInput: { maxLength: 15 } }} /><div className="field-grid"><TextField required label="Série DPS" type="number" value={profile.series} onChange={e => change('series', e.target.value)} slotProps={{ htmlInput: { min: 1, max: 49999, step: 1 } }} /><TextField required label="Último número DPS utilizado" type="number" value={profile.lastDpsNumber} onChange={e => change('lastDpsNumber', e.target.value)} slotProps={{ htmlInput: { min: 0, max: 999999999999998, step: 1 } }} /></div><Typography variant="caption" color="text.secondary">Numeração separada por série e ambiente. Informe o último número já usado em outros emissores. O próximo será reservado automaticamente; números não podem retroceder.</Typography><Button type="submit" variant="contained" disabled={!!busy || storedProfile.loading}>Salvar enquadramento</Button></div></Paper>
        <Paper component="form" onSubmit={e => save(e, 'proxy')} variant="outlined" sx={{ p: 3 }}><Typography variant="h6" sx={{ mb: 1 }}>Procuração do cliente</Typography><Typography variant="body2" color="text.secondary" sx={{ mb: 3 }}>A procuração deve existir nos serviços oficiais e contemplar a NFS-e. Este cadastro registra o vínculo e os poderes declarados.</Typography><div className="form-stack"><TextField select required label="A1 do procurador" value={proxy.certificateId} onChange={e => setProxy(p => ({ ...p, certificateId: e.target.value }))}><MenuItem value="">Selecione o certificado</MenuItem>{certificates.data?.filter(c => c.status === 'ACTIVE').map(c => <MenuItem key={c.id} value={c.id}>{c.label} · {c.holderTaxId}</MenuItem>)}</TextField><TextField required label="Referência da procuração oficial" value={proxy.reference} onChange={e => setProxy(p => ({ ...p, reference: e.target.value }))} slotProps={{ htmlInput: { maxLength: 200 } }} /><div className="field-grid"><TextField required type="date" label="Válida a partir de" value={proxy.validFrom} onChange={e => setProxy(p => ({ ...p, validFrom: e.target.value }))} slotProps={{ inputLabel: { shrink: true } }} /><TextField required type="date" label="Válida até" value={proxy.validTo} onChange={e => setProxy(p => ({ ...p, validTo: e.target.value }))} slotProps={{ inputLabel: { shrink: true } }} /></div><Box>{[['canIssue', 'Emitir NFS-e'], ['canQuery', 'Consultar documentos e resultados'], ['canCancel', 'Solicitar cancelamento'], ['active', 'Vínculo ativo']].map(([key, label]) => <FormControlLabel key={key} sx={{ display: 'flex' }} control={<Checkbox checked={proxy[key]} onChange={e => setProxy(p => ({ ...p, [key]: e.target.checked }))} />} label={label} />)}</Box>{storedProxy.data && <Chip label={{ DECLARED: 'Poderes declarados', ACCEPTED_BY_FISCO: 'Operação aceita pelo Fisco', REFUSED_BY_FISCO: 'Acesso recusado pelo Fisco' }[storedProxy.data.verificationStatus]} size="small" />}<Button type="submit" variant="contained" disabled={!!busy || storedProxy.loading}>Salvar procuração</Button></div></Paper>
      </div></>}
    </>}
    <Dialog open={open} onClose={() => !busy && setOpen(false)} fullWidth maxWidth="sm"><form onSubmit={importCertificate}><DialogTitle>Cadastrar certificado A1</DialogTitle><DialogContent><ErrorBox error={error} /><Alert severity="info" sx={{ mb: 3 }}>Utilize o A1 do titular que recebeu as procurações. O arquivo e a senha serão protegidos no servidor.</Alert><div className="form-stack"><TextField required label="Nome para identificação" value={certificate.label} onChange={e => setCertificate(c => ({ ...c, label: e.target.value }))} slotProps={{ htmlInput: { maxLength: 120 } }} /><TextField required label="CPF ou CNPJ do titular do A1" value={certificate.holderTaxId} onChange={e => setCertificate(c => ({ ...c, holderTaxId: e.target.value.replace(/\D/g, '').slice(0, 14) }))} slotProps={{ htmlInput: { pattern: '[0-9]{11}|[0-9]{14}', inputMode: 'numeric' } }} /><label>Arquivo A1 (.pfx ou .p12)<input style={{ display: 'block', marginTop: 10, maxWidth: '100%' }} aria-label="Arquivo A1" type="file" accept=".pfx,.p12" required onChange={e => setFile(e.target.files?.[0] || null)} /></label><TextField required type="password" label="Senha do arquivo A1" autoComplete="off" value={certificate.password} onChange={e => setCertificate(c => ({ ...c, password: e.target.value }))} /></div></DialogContent><DialogActions sx={{ p: 3 }}><Button color="secondary" onClick={() => { setOpen(false); setCertificate(c => ({ ...c, password: '' })); setFile(null); }} disabled={!!busy}>Cancelar</Button><Button variant="contained" type="submit" disabled={!!busy}>{busy ? 'Validando…' : 'Importar A1'}</Button></DialogActions></form></Dialog>
    <Snackbar open={!!notice} autoHideDuration={5500} onClose={() => setNotice('')}><Alert severity="success" onClose={() => setNotice('')}>{notice}</Alert></Snackbar>
  </>;
}
