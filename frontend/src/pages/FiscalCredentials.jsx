import { useEffect, useState } from 'react';
import { Alert, Box, Button, Checkbox, Chip, Dialog, DialogActions, DialogContent, DialogTitle, FormControlLabel, IconButton, InputAdornment, MenuItem, Paper, Snackbar, Table, TableBody, TableCell, TableContainer, TableHead, TableRow, TextField, Typography } from '@mui/material';
import CloseRounded from '@mui/icons-material/CloseRounded';
import UploadFileOutlined from '@mui/icons-material/UploadFileOutlined';
import LockOutlined from '@mui/icons-material/LockOutlined';
import VisibilityOutlined from '@mui/icons-material/VisibilityOutlined';
import VisibilityOffOutlined from '@mui/icons-material/VisibilityOffOutlined';
import AddRounded from '@mui/icons-material/AddRounded';
import VerifiedUserOutlined from '@mui/icons-material/VerifiedUserOutlined';
import { useAuth, useResource, useWorkspace } from '../context';
import { date } from '../api';
import { EmptyState, ErrorBox, Loading, PageHeading } from '../components/Common';

const initialProfile = { simplesOption: '3', simplesAssessment: '1', specialRegime: '0', municipalRegistration: '', series: '1', lastDpsNumber: 0 };
const initialProxy = () => ({ certificateId: '', reference: '', validFrom: '', validTo: '', canIssue: true, canQuery: true, canCancel: true, active: true });

export default function FiscalCredentials() {
  const { issuerId } = useWorkspace();
  return <Credentials key={issuerId} />;
}
export function Credentials({ fixedIssuerId, embedded = false, onConfigured, onBusyChange, focusMunicipalRegistration = false }) {
  const { api } = useAuth();
  const { issuerId: workspaceIssuerId, setIssuerId, companies, isManager } = useWorkspace();
  const issuerId = fixedIssuerId || workspaceIssuerId;
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
  const [showPassword, setShowPassword] = useState(false);
  const [busy, setBusy] = useState('');
  const [error, setError] = useState(null);
  const [notice, setNotice] = useState('');
  const [proxyError, setProxyError] = useState(null);
  const [proxyAttempted, setProxyAttempted] = useState(false);
  const [proxySaved, setProxySaved] = useState(false);
  const proxyIssues = { certificateId: !proxy.certificateId, reference: !proxy.reference.trim() };
  function changeProxy(key, value) { setProxy(p => ({ ...p, [key]: value })); setProxyError(null); setProxySaved(false); }
  useEffect(() => { onBusyChange?.(!!busy); }, [busy, onBusyChange]);
  useEffect(() => { if (storedProfile.data) setProfile(storedProfile.data); }, [storedProfile.data]);
  useEffect(() => {
    if (storedProxy.data) {
      const { certificateId, reference, validFrom, validTo, canIssue, canQuery, canCancel, active } = storedProxy.data;
      setProxy({ certificateId, reference, validFrom, validTo, canIssue, canQuery, canCancel, active }); setProxySaved(true);
    }
  }, [storedProxy.data]);
  function closeCertificate() {
    if (busy) return;
    setOpen(false); setFile(null); setShowPassword(false);
    setCertificate({ label: '', holderTaxId: '', password: '' }); setError(null);
  }
  async function importCertificate(event) {
    event.preventDefault(); setError(null);
    if (!file) { setError(new Error('Selecione o arquivo A1 (.pfx ou .p12).')); return; }
    if (file.size > 1024 * 1024) { setError(new Error('O arquivo excede 1 MB.')); return; }
    setBusy('certificate');
    try {
      const bytes = new Uint8Array(await file.arrayBuffer()); let binary = '';
      for (let offset = 0; offset < bytes.length; offset += 8192) binary += String.fromCharCode(...bytes.subarray(offset, offset + 8192));
      const imported = await api('/nfse/certificates', { method: 'POST', body: { ...certificate, pfxBase64: btoa(binary) } });
      setProxy(p => ({ ...p, certificateId: imported.id })); setProxySaved(false);
      setCertificate({ label: '', holderTaxId: '', password: '' }); setFile(null); setShowPassword(false); setOpen(false); certificates.reload(); onConfigured?.('certificate'); setNotice('A1 validado e armazenado com proteção criptográfica.');
    } catch (e) { setError(e); } finally { setBusy(''); }
  }
  async function save(event, kind) {
    event.preventDefault(); event.stopPropagation(); setError(null);
    if (kind === 'proxy') {
      setProxyAttempted(true); setProxyError(null);
      if (proxyIssues.certificateId || proxyIssues.reference) {
        setProxyError(new Error('Preencha os campos destacados antes de salvar a procuração.')); return;
      }
    }
    setBusy(kind);
    try {
      await api(`/nfse/issuers/${issuerId}/${kind}`, { method: 'PUT', body: kind === 'profile' ? { ...profile, lastDpsNumber: Number(profile.lastDpsNumber) } : { ...proxy, validFrom: proxy.validFrom || null, validTo: proxy.validTo || null } });
      (kind === 'profile' ? storedProfile : storedProxy).reload(); onConfigured?.(kind); setNotice(kind === 'profile' ? 'Enquadramento e série salvos.' : 'Procuração vinculada. A aceitação dos poderes será validada pelo Fisco.');
    } catch (e) { if (kind === 'proxy') setProxyError(e); else setError(e); } finally { setBusy(''); }
  }
  async function revoke(id) {
    if (!window.confirm('Revogar este A1 no sistema? Novas assinaturas com ele serão bloqueadas.')) return;
    setBusy(id); setError(null);
    try { await api(`/nfse/certificates/${id}`, { method: 'DELETE' }); certificates.reload(); setNotice('Certificado revogado no sistema.'); }
    catch (e) { setError(e); } finally { setBusy(''); }
  }
  const activeCertificates = certificates.data?.filter(c => c.status === 'ACTIVE') || [];
  const change = (key, value) => setProfile(p => ({ ...p, [key]: value }));
  return <>{!embedded && <PageHeading title="Certificados e procurações" subtitle="O A1 do contador e os poderes para atuar em nome de cada cliente." action={isManager && <Button variant="contained" startIcon={<AddRounded />} onClick={() => { setError(null); setOpen(true); }}>Cadastrar A1</Button>} />}
    {embedded && <Box sx={{ mb: 2, display: 'flex', justifyContent: 'flex-end' }}><Button variant="outlined" startIcon={<AddRounded />} onClick={() => { setError(null); setOpen(true); }}>Cadastrar A1</Button></Box>}
    <ErrorBox error={error || config.error || certificates.error} />
    {config.data && <Alert severity={config.data.environment === 'PRODUCAO' ? 'warning' : 'info'} sx={{ mb: 3 }}>Ambiente: <strong>{config.data.environment === 'PRODUCAO' ? 'Produção' : 'Homologação (testes)'}</strong>. {!config.data.vaultConfigured || !config.data.trustConfigured ? 'O administrador precisa configurar o cofre e as âncoras ICP-Brasil no servidor antes de importar o A1.' : 'O certificado será validado antes de cada assinatura e comunicação.'}</Alert>}
    {!isManager ? <Alert severity="info">O cadastro de certificados, procurações e enquadramentos é administrado pelo contador.</Alert> : <>
      <Paper variant="outlined" sx={{ mb: 3 }}><div className="panel-header"><Typography variant="h6">A1 do escritório / representante</Typography><Chip label={`${certificates.data?.length || 0} certificados`} size="small" /></div>{certificates.loading ? <Loading /> : !certificates.data?.length ? <EmptyState icon={<VerifiedUserOutlined />} title="Cadastre o certificado do contador" description="O mesmo A1 pode atender vários clientes, desde que cada um tenha procuração válida para os serviços da NFS-e." /> : <TableContainer><Table aria-label="Certificados A1"><TableHead><TableRow><TableCell>Identificação</TableCell><TableCell>CPF/CNPJ titular</TableCell><TableCell>Validade</TableCell><TableCell>Situação</TableCell><TableCell /></TableRow></TableHead><TableBody>{certificates.data.map(c => <TableRow key={c.id}><TableCell>{c.label}</TableCell><TableCell>{c.holderTaxId}</TableCell><TableCell>{date(c.expiresAt.slice(0, 10))}</TableCell><TableCell><Chip size="small" label={c.status === 'ACTIVE' ? 'Cadastrado' : 'Revogado'} /></TableCell><TableCell>{c.status === 'ACTIVE' && <Button color="error" size="small" disabled={!!busy} onClick={() => revoke(c.id)}>Revogar</Button>}</TableCell></TableRow>)}</TableBody></Table></TableContainer>}</Paper>
      {!embedded && <Paper variant="outlined" className="filter-bar"><TextField select label="Empresa cliente" value={issuerId} onChange={e => setIssuerId(e.target.value)}><MenuItem value="">Selecione uma empresa</MenuItem>{companies.data?.map(c => <MenuItem key={c.id} value={c.id}>{c.name}</MenuItem>)}</TextField></Paper>}
      {issuerId && <><ErrorBox error={storedProfile.error?.status === 422 ? null : storedProfile.error} /><ErrorBox error={storedProxy.error?.status === 422 ? null : storedProxy.error} /><div className="field-grid" style={{ alignItems: 'start' }}>
        <Paper component="form" onSubmit={e => save(e, 'profile')} variant="outlined" sx={{ p: 3 }}><Typography variant="h6" sx={{ mb: 3 }}>Enquadramento e numeração</Typography><div className="form-stack"><TextField select label="Situação no Simples Nacional" value={profile.simplesOption} onChange={e => change('simplesOption', e.target.value)}><MenuItem value="1">Não optante</MenuItem><MenuItem value="2">MEI</MenuItem><MenuItem value="3">ME/EPP optante</MenuItem></TextField>{profile.simplesOption === '3' && <TextField select label="Regime de apuração" value={profile.simplesAssessment} onChange={e => change('simplesAssessment', e.target.value)}><MenuItem value="1">Federais e ISS pelo Simples</MenuItem><MenuItem value="2">Federais pelo Simples; ISS fora</MenuItem><MenuItem value="3">Federais e ISS fora do Simples</MenuItem></TextField>}<TextField select label="Regime especial" value={profile.specialRegime} onChange={e => change('specialRegime', e.target.value)}>{[['0', 'Nenhum'], ['1', 'Ato cooperado'], ['2', 'Estimativa'], ['3', 'Microempresa municipal'], ['4', 'Notário / Registrador'], ['5', 'Profissional autônomo'], ['6', 'Sociedade de profissionais'], ['9', 'Outros']].map(([v, label]) => <MenuItem key={v} value={v}>{label}</MenuItem>)}</TextField>{registration.data?.municipalRegistration && <Button variant="outlined" disabled={!!busy || registration.data.municipalRegistration.length > 15} onClick={() => change('municipalRegistration', registration.data.municipalRegistration)}>Usar inscrição municipal importada</Button>}<TextField autoFocus={focusMunicipalRegistration} label="Inscrição municipal (quando exigida)" helperText={companies.data?.find(c => c.id === issuerId)?.municipality === '3550308' ? 'CCM de São Paulo: informe os dígitos, sem pontos ou hífen.' : 'Informe a inscrição do prestador conforme o cadastro municipal.'} value={profile.municipalRegistration || ''} onChange={e => change('municipalRegistration', companies.data?.find(c => c.id === issuerId)?.municipality === '3550308' ? e.target.value.replace(/[.\s-]/g, '') : e.target.value)} slotProps={{ htmlInput: { maxLength: 15 } }} /><div className="field-grid"><TextField required label="Série DPS" type="number" value={profile.series} onChange={e => change('series', e.target.value)} slotProps={{ htmlInput: { min: 1, max: 49999, step: 1 } }} /><TextField required label="Último número DPS utilizado" type="number" value={profile.lastDpsNumber} onChange={e => change('lastDpsNumber', e.target.value)} slotProps={{ htmlInput: { min: 0, max: 999999999999998, step: 1 } }} /></div><Typography variant="caption" color="text.secondary">Numeração separada por série e ambiente. Informe o último número já usado em outros emissores. O próximo será reservado automaticamente; números não podem retroceder.</Typography><Button type="submit" variant="contained" disabled={!!busy || storedProfile.loading}>Salvar enquadramento</Button></div></Paper>
        <Paper component="form" noValidate onSubmit={e => save(e, 'proxy')} variant="outlined" sx={{ p: 3 }}><Typography variant="h6" sx={{ mb: 1 }}>Procuração do cliente</Typography><Typography variant="body2" color="text.secondary" sx={{ mb: 3 }}>A procuração deve existir nos serviços oficiais e contemplar a NFS-e. Este cadastro registra o vínculo e os poderes declarados.</Typography><ErrorBox error={proxyError} />{proxySaved && !proxyError && <Alert severity="success" sx={{ mb: 2 }}>Procuração salva para este prestador. Será reutilizada nas próximas notas.</Alert>}<div className="form-stack">{certificates.loading ? <Typography role="status">Carregando certificados…</Typography> : certificates.error ? <Alert severity="error" action={<Button onClick={certificates.reload}>Tentar novamente</Button>}>Não foi possível carregar os certificados.</Alert> : !activeCertificates.length && <Alert severity="warning" action={<Button onClick={() => { setError(null); setOpen(true); }}>Cadastrar A1</Button>}>Nenhum A1 ativo cadastrado. Importe o certificado do procurador para selecioná-lo nesta procuração.</Alert>}<TextField select required label="A1 do procurador" error={proxyAttempted && proxyIssues.certificateId} disabled={!!busy || certificates.loading || !activeCertificates.length} helperText={proxyAttempted && proxyIssues.certificateId ? 'Selecione um certificado A1 cadastrado.' : activeCertificates.length ? 'Selecione o A1 do titular da procuração.' : 'Cadastre um A1 ativo para habilitar a seleção.'} value={proxy.certificateId} onChange={e => changeProxy('certificateId', e.target.value)}><MenuItem value="">Selecione o certificado</MenuItem>{activeCertificates.map(c => <MenuItem key={c.id} value={c.id}>{c.label} · {c.holderTaxId}</MenuItem>)}</TextField><TextField required label="Referência da procuração oficial" error={proxyAttempted && proxyIssues.reference} helperText={proxyAttempted && proxyIssues.reference ? 'Informe a referência da procuração oficial para este prestador.' : 'Identificação ou referência do vínculo concedido nos serviços oficiais.'} value={proxy.reference} onChange={e => changeProxy('reference', e.target.value)} slotProps={{ htmlInput: { maxLength: 200 } }} /><div className="field-grid"><TextField type="date" label="Válida a partir de" helperText="Opcional: sem limite inicial se não informada." value={proxy.validFrom || ''} onChange={e => changeProxy('validFrom', e.target.value)} slotProps={{ inputLabel: { shrink: true } }} /><TextField type="date" label="Válida até" helperText="Opcional: sem limite final declarado se não informada." value={proxy.validTo || ''} onChange={e => changeProxy('validTo', e.target.value)} slotProps={{ inputLabel: { shrink: true } }} /></div><Box>{[['canIssue', 'Emitir NFS-e'], ['canQuery', 'Consultar documentos e resultados'], ['canCancel', 'Solicitar cancelamento'], ['active', 'Vínculo ativo']].map(([key, label]) => <FormControlLabel key={key} sx={{ display: 'flex' }} control={<Checkbox checked={proxy[key]} onChange={e => changeProxy(key, e.target.checked)} />} label={label} />)}</Box>{storedProxy.data && <Chip label={{ DECLARED: 'Poderes declarados', ACCEPTED_BY_FISCO: 'Operação aceita pelo Fisco', REFUSED_BY_FISCO: 'Acesso recusado pelo Fisco' }[storedProxy.data.verificationStatus]} size="small" />}<Button type="submit" variant="contained" disabled={!!busy || storedProxy.loading || certificates.loading}>Salvar procuração</Button></div></Paper>
      </div></>}
    </>}
    <Dialog open={open} onClose={closeCertificate} fullWidth maxWidth="sm" className="certificate-dialog" aria-labelledby="certificate-dialog-title">
      <form onSubmit={importCertificate}>
        <Box className="certificate-dialog-header"><Box className="certificate-heading-icon"><VerifiedUserOutlined /></Box><Box sx={{ flex: 1, minWidth: 0 }}><Typography variant="overline" color="primary">CERTIFICADO DIGITAL</Typography><DialogTitle id="certificate-dialog-title" sx={{ p: 0 }}>Cadastrar certificado A1</DialogTitle><Typography variant="body2" color="text.secondary">Identifique o titular e importe o arquivo do procurador.</Typography></Box><IconButton aria-label="Fechar cadastro de certificado" disabled={!!busy} onClick={closeCertificate}><CloseRounded /></IconButton></Box>
        <DialogContent sx={{ px: { xs: 2.5, sm: 3.5 }, py: 3 }}><ErrorBox error={error} />
          <Box className="certificate-security-note"><LockOutlined fontSize="small" /><Typography variant="body2">Use o A1 de quem recebeu as procurações. O arquivo e a senha são protegidos no servidor.</Typography></Box>
          <Typography variant="subtitle2" sx={{ mt: 3, mb: 2 }}>Dados do titular</Typography>
          <Box sx={{ display: 'grid', gap: 2.5 }}>
            <TextField autoFocus required disabled={!!busy} label="Nome para identificação" placeholder="Ex.: Certificado do escritório" autoComplete="off" value={certificate.label} onChange={e => setCertificate(c => ({ ...c, label: e.target.value }))} slotProps={{ inputLabel: { shrink: true }, htmlInput: { maxLength: 120 } }} />
            <TextField required disabled={!!busy} label="CPF ou CNPJ do titular do A1" placeholder="Somente números" autoComplete="off" value={certificate.holderTaxId} onChange={e => setCertificate(c => ({ ...c, holderTaxId: e.target.value.replace(/\D/g, '').slice(0, 14) }))} slotProps={{ inputLabel: { shrink: true }, htmlInput: { pattern: '[0-9]{11}|[0-9]{14}', inputMode: 'numeric' } }} />
          </Box>
          <Typography variant="subtitle2" sx={{ mt: 3, mb: 1.5 }}>Arquivo do certificado <Box component="span" color="primary.main">*</Box></Typography>
          <Box className={`certificate-upload${file ? ' certificate-upload-selected' : ''}`}>
            <UploadFileOutlined sx={{ color: 'primary.main', fontSize: 32 }} />
            <Box sx={{ minWidth: 0, flex: 1 }}><Typography variant="body2" fontWeight={600} sx={{ overflowWrap: 'anywhere' }}>{file ? file.name : 'Selecione seu certificado A1'}</Typography><Typography variant="caption" color="text.secondary">{file ? `${(file.size / 1024).toFixed(1)} KB · Pronto para importar` : 'Formatos .pfx ou .p12 · Até 1 MB'}</Typography></Box>
            <Button component="label" variant="outlined" disabled={!!busy} size="small">{file ? 'Trocar arquivo' : 'Escolher arquivo'}<input className="certificate-file-input" aria-label="Arquivo A1" type="file" accept=".pfx,.p12" required disabled={!!busy} onChange={e => { const next = e.target.files?.[0]; if (next) setFile(next); }} /></Button>
          </Box>
          <TextField required disabled={!!busy} type={showPassword ? 'text' : 'password'} label="Senha do arquivo A1" placeholder="Informe a senha do certificado" autoComplete="new-password" value={certificate.password} onChange={e => setCertificate(c => ({ ...c, password: e.target.value }))} sx={{ mt: 3 }} helperText="Senha definida na emissão do certificado digital." slotProps={{ inputLabel: { shrink: true }, input: { endAdornment: <InputAdornment position="end"><IconButton edge="end" aria-label={showPassword ? 'Ocultar senha do certificado' : 'Mostrar senha do certificado'} disabled={!!busy} onClick={() => setShowPassword(value => !value)}>{showPassword ? <VisibilityOffOutlined /> : <VisibilityOutlined />}</IconButton></InputAdornment> } }} />
          <Typography variant="caption" color="text.secondary" display="block" sx={{ mt: 2 }}>* Campos obrigatórios.</Typography>
        </DialogContent>
        <DialogActions className="certificate-dialog-actions"><Button color="secondary" onClick={closeCertificate} disabled={!!busy}>Cancelar</Button><Button variant="contained" startIcon={<UploadFileOutlined />} type="submit" disabled={!!busy}>{busy ? 'Validando…' : 'Importar A1'}</Button></DialogActions>
      </form>
    </Dialog>
    <Snackbar open={!!notice} autoHideDuration={5500} onClose={() => setNotice('')}><Alert severity="success" onClose={() => setNotice('')}>{notice}</Alert></Snackbar>
  </>;
}
