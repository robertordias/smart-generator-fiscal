import { useEffect, useRef, useState } from 'react';
import { Link } from 'react-router-dom';
import { Alert, Box, Button, Checkbox, Chip, Dialog, DialogActions, DialogContent, DialogTitle, FormControlLabel, IconButton, MenuItem, Paper, Step, StepLabel, Stepper, Table, TableBody, TableCell, TableContainer, TableHead, TableRow, TextField, Typography } from '@mui/material';
import AddRounded from '@mui/icons-material/AddRounded';
import DeleteOutlineRounded from '@mui/icons-material/DeleteOutlineRounded';
import CheckRounded from '@mui/icons-material/CheckRounded';
import { useAuth, useResource, useWorkspace } from '../context';
import { cnpj, date, money, query, today } from '../api';
import { decimal, defaultTaxes, payments, quote, subtotal, taxFields } from '../invoice';
import { DraftDetails, EmptyState, ErrorBox, FieldValue, Loading, PageHeading } from '../components/Common';
import RecipientEditor from '../components/RecipientEditor';
import ApproximateTaxes from '../components/ApproximateTaxes';

const blankRecipient = () => ({ taxId: '', name: '', email: '', address: null });
export default function NewDraft() {
  const { api } = useAuth();
  const { companies, issuerId, setIssuerId, selectedIssuer, isManager } = useWorkspace();
  const [step, setStep] = useState(0);
  const [competence, setCompetence] = useState(today());
  const [variantName, setVariantName] = useState('Padrão');
  const services = useResource(issuerId ? `/issuers/${issuerId}/services?${query({ competence })}` : null);
  const policy = useResource(issuerId ? `/issuers/${issuerId}/defaults?${query({ competence, variantName })}` : null);
  const recipients = useResource(issuerId ? `/issuers/${issuerId}/recipients` : null);
  const config = useResource('/nfse/config');
  const profile = useResource(issuerId ? `/nfse/issuers/${issuerId}/profile` : null);
  const proxy = useResource(issuerId ? `/nfse/issuers/${issuerId}/proxy` : null);
  const [recipientId, setRecipientId] = useState('');
  const [recipient, setRecipient] = useState(blankRecipient);
  const [editor, setEditor] = useState(false);
  const [items, setItems] = useState([]);
  const [quantity, setQuantity] = useState('1');
  const [unitPrice, setUnitPrice] = useState('');
  const [manual, setManual] = useState(false);
  const [description, setDescription] = useState('');
  const [reason, setReason] = useState('');
  const [paymentMethod, setPaymentMethod] = useState('NONE');
  const [dueDate, setDueDate] = useState('');
  const [discount, setDiscount] = useState('0');
  const [discountType, setDiscountType] = useState('UNCONDITIONAL');
  const [complement, setComplement] = useState('');
  const [serviceMunicipality, setServiceMunicipality] = useState('');
  const [municipalServiceCode, setMunicipalServiceCode] = useState('');
  const [taxes, setTaxes] = useState(() => defaultTaxes());
  const [cst, setCst] = useState('01');
  const [approxMode, setApproxMode] = useState('');
  const [approx, setApprox] = useState({ simples: '', federal: '', state: '', municipal: '' });
  const [error, setError] = useState(null);
  const [busy, setBusy] = useState(false);
  const [outcome, setOutcome] = useState(null);
  const [details, setDetails] = useState(false);
  const [confirm, setConfirm] = useState(null);
  const [typed, setTyped] = useState('');
  const attempt = useRef(null);
  const values = policy.data?.values;
  const unconditional = discountType === 'UNCONDITIONAL' ? discount || '0' : '0';
  const conditional = discountType === 'CONDITIONAL' ? discount || '0' : '0';
  const totals = quote(items, unconditional, conditional, taxes);
  const resolvedDescription = manual ? description : values?.description || '';
  const changedTaxes = !!values && taxFields.some(([key, , rate]) => key === 'iss' ? taxes.iss.enabled !== values.issWithheld || Number(taxes.iss.rate) !== Number(values.issRate) : Number(taxes[key].enabled ? taxes[key].rate : 0) !== Number(values[rate]));
  const missingSetup = profile.error || proxy.error || (!config.data?.vaultConfigured || !config.data?.trustConfigured ? new Error('Configure o A1, a procuração e o enquadramento do emitente antes de gerar a nota.') : null);

  useEffect(() => { setVariantName('Padrão'); setRecipientId(''); setRecipient(blankRecipient()); setItems([]); setStep(0); setError(null); setEditor(false); }, [issuerId]);
  useEffect(() => { if (services.data?.length) setVariantName(current => services.data.some(service => service.values.variantName === current) ? current : services.data[0].values.variantName); }, [services.data]);
  useEffect(() => { setTaxes(defaultTaxes(values, totals.issBase)); setManual(false); setDescription(''); setReason(''); }, [policy.data?.id]);
  useEffect(() => { setTaxes(current => Object.fromEntries(Object.entries(current).map(([key, tax]) => [key, { ...tax, base: key === 'iss' || !tax.manualBase ? totals.issBase : tax.base }]))); }, [totals.issBase]);

  function chooseRecipient(saved) {
    setRecipientId(saved?.id || ''); setRecipient(saved ? { ...saved.values, email: saved.values.email || '', address: saved.values.address ? { ...saved.values.address } : null } : blankRecipient());
  }
  function taxChange(key, field, value) { setTaxes(current => ({ ...current, [key]: { ...current[key], [field]: value, ...(field === 'base' ? { manualBase: true } : {}) } })); }
  function fail(message) { setError(new Error(message)); return false; }
  function valid(stage) {
    if (!issuerId || !competence) return fail('Selecione o emitente e a competência.');
    if (!recipient.name.trim() || !/^(\d{11}|\d{14})$/.test(recipient.taxId)) return fail('Informe o CPF/CNPJ e o nome do tomador.');
    if (stage >= 1 && (!values || !items.length || Number(totals.gross) <= 0)) return fail('Selecione o serviço e adicione pelo menos um item com valor positivo.');
    if (stage >= 2 && (Number(totals.net) < 0 || Number(discount) < 0)) return fail('Revise os descontos e as retenções: o líquido estimado não pode ser negativo.');
    if (stage >= 2 && (manual || changedTaxes) && !reason.trim()) return fail('Justifique as alterações na descrição, nas alíquotas ou nas retenções do padrão.');
    setError(null); return true;
  }
  function addItem() {
    if (!values || Number(quantity) <= 0 || Number(unitPrice) <= 0) return fail('Informe quantidade e valor unitário maiores que zero.');
    if (items.length >= 20) return fail('Adicione até 20 itens por nota.');
    setItems(current => [...current, { quantity, unitPrice }]); setQuantity('1'); setUnitPrice(''); setError(null);
  }
  function body() {
    return { issuerId, competence, variantName, amount: totals.gross, ...(manual ? { description } : {}), ...(reason.trim() ? { overrideReason: reason.trim() } : {}), commercial: {
      recipient: { ...recipient, name: recipient.name.trim(), email: recipient.email.trim() || null }, items, paymentMethod, dueDate: dueDate || null,
      unconditionalDiscount: unconditional, conditionalDiscount: conditional, complement: complement.trim(),
      serviceMunicipality, municipalServiceCode, taxes: { ...Object.fromEntries(taxFields.map(([key]) => [key, { enabled: taxes[key].enabled, rate: taxes[key].rate || '0', base: taxes[key].base || '0' }])), pisCofinsCst: cst },
      approximation: approxMode ? { mode: approxMode, ...Object.fromEntries(Object.entries(approx).map(([key, value]) => [key, value === '' ? null : value])) } : null,
    } };
  }
  async function save(generate = false) {
    if (busy || !valid(2)) return;
    if (generate && !approxMode) return fail('Escolha como informar os tributos aproximados antes de gerar a nota.');
    setBusy(true); setError(null);
    try {
      const payload = body(); const serialized = JSON.stringify(payload);
      if (attempt.current?.body !== serialized) attempt.current = { body: serialized, key: crypto.randomUUID() };
      const draft = await api('/drafts', { method: 'POST', body: payload, headers: { 'Idempotency-Key': attempt.current.key } });
      if (generate) { setConfirm(draft); setTyped(''); } else setOutcome({ kind: 'draft', draft });
    } catch (e) { setError(e); } finally { setBusy(false); }
  }
  async function generate(event) {
    event.preventDefault(); if (busy || typed !== selectedIssuer?.cnpj) return;
    setBusy(true); setError(null); let prepared = null;
    try {
      prepared = await api('/nfse/prepare', { method: 'POST', body: { draftId: confirm.id, recipient: null, serviceMunicipality: '', municipalServiceCode: '', nbs: '' } });
      const note = await api(`/nfse/notes/${prepared.id}/transmit`, { method: 'POST', body: { confirmIssuerTaxId: typed } });
      setOutcome({ kind: 'note', draft: confirm, note }); setConfirm(null);
    } catch (e) {
      setError(e);
      if (prepared) { setOutcome({ kind: 'pending', draft: confirm, note: prepared }); setConfirm(null); }
    } finally { setBusy(false); }
  }
  function next(event) { event.preventDefault(); if (step < 2) { if (valid(step)) setStep(step + 1); } else save(isManager); }
  function restart() { setOutcome(null); setItems([]); setStep(0); setError(null); setConfirm(null); attempt.current = null; }

  if (outcome) {
    const note = outcome.note; const authorized = note?.status === 'AUTHORIZED';
    return <><PageHeading title={outcome.kind === 'draft' ? 'Rascunho preparado' : authorized ? 'Nota fiscal autorizada' : 'Acompanhar emissão'} subtitle={outcome.kind === 'draft' ? 'Documento, tomador, serviços e condições preservados.' : 'A situação fiscal é confirmada pela resposta do Emissor Nacional.'} /><ErrorBox error={error} /><Paper variant="outlined" className="success-panel"><CheckRounded color="primary" sx={{ fontSize: 44 }} /><Typography variant="h5" sx={{ my: 2 }}>{outcome.kind === 'draft' ? 'Tudo salvo com sucesso.' : authorized ? `NFS-e ${note.officialNumber} autorizada` : 'Consulte a situação antes de tentar outra emissão.'}</Typography><Typography color="text.secondary">{selectedIssuer?.name} · {date(outcome.draft.competence)}</Typography><Typography variant="h4" sx={{ my: 3 }}>{money(outcome.draft.amount)}</Typography><Alert severity={authorized ? 'success' : 'info'} sx={{ textAlign: 'left' }}>{outcome.kind === 'draft' ? 'Este é um rascunho sem validade fiscal. A transmissão precisa ser confirmada.' : note?.lastMessage || 'A DPS foi preparada. Acompanhe a confirmação na tela de notas fiscais.'}</Alert><div className="success-actions"><Button variant="outlined" onClick={() => setDetails(true)}>Ver detalhes</Button>{note ? <Button variant="contained" component={Link} to={`/notas-fiscais?note=${note.id}`}>Acompanhar nota</Button> : <><Button component={Link} to="/rascunhos">Ir para rascunhos</Button><Button variant="contained" component={Link} to={`/notas-fiscais?draft=${outcome.draft.id}`}>Preparar emissão nacional</Button></>}{authorized && <Button component="a" href={note.consultationUrl} target="_blank" rel="noopener noreferrer">Abrir DANFSe oficial</Button>}<Button onClick={restart}>Preparar outra nota</Button></div></Paper><DraftDetails draft={details ? outcome.draft : null} onClose={() => setDetails(false)} issuerName={selectedIssuer?.name} /></>;
  }
  return <><PageHeading title="Emitir nota" subtitle="Documento e destinatário, serviços, pagamento e emissão em um único fluxo." action={<Button variant="outlined" disabled={busy || !items.length} onClick={() => save(false)}>Salvar rascunho</Button>} />
    {config.data && <Alert severity={config.data.environment === 'PRODUCAO' ? 'warning' : 'info'} sx={{ mb: 3 }}>NFS-e Nacional · <strong>{config.data.environment === 'PRODUCAO' ? 'PRODUÇÃO' : 'HOMOLOGAÇÃO — sem validade fiscal'}</strong> · {selectedIssuer?.name || 'Selecione o emitente'}</Alert>}
    <ErrorBox error={companies.error || config.error} onRetry={() => { companies.reload(); config.reload(); }} />
    {companies.loading ? <Loading /> : !companies.data?.length ? <Paper variant="outlined"><EmptyState title="Cadastre o emitente" description="Uma empresa e seus padrões fiscais são necessários para preparar a nota." action={<Button component={Link} to="/empresas">Ir para empresas</Button>} /></Paper> : <div className="editor-layout invoice-layout"><Paper variant="outlined" component="form" onSubmit={next} className="draft-editor"><Stepper activeStep={step} alternativeLabel sx={{ mb: 4 }}>{['Documento e destinatário', 'Serviços', 'Pagamento e emissão'].map(label => <Step key={label}><StepLabel>{label}</StepLabel></Step>)}</Stepper><ErrorBox error={error} />
      {step === 0 && <><Typography variant="h6" sx={{ mb: 2 }}>Documento</Typography><div className="field-grid"><TextField select required label="Empresa emitente" value={issuerId} onChange={e => setIssuerId(e.target.value)} disabled={busy}><MenuItem value="">Selecione uma empresa</MenuItem>{companies.data.map(company => <MenuItem key={company.id} value={company.id}>{company.name}</MenuItem>)}</TextField><TextField required type="date" label="Competência" value={competence} onChange={e => setCompetence(e.target.value)} slotProps={{ inputLabel: { shrink: true } }} /><TextField label="Tipo de documento" value="NFS-e · Nota Fiscal de Serviço" slotProps={{ input: { readOnly: true } }} /><TextField label="Natureza da operação" value="Prestação de serviços" slotProps={{ input: { readOnly: true } }} /></div>
        <Typography variant="h6" sx={{ mt: 4, mb: 2 }}>Tomador e destinatário</Typography><ErrorBox error={recipients.error} onRetry={recipients.reload} /><div className="field-grid"><TextField select label="Tomador cadastrado" value={recipientId} slotProps={{ inputLabel: { shrink: true }, select: { displayEmpty: true } }} onChange={e => chooseRecipient(recipients.data?.find(row => row.id === e.target.value))}><MenuItem value="">Informar manualmente</MenuItem>{recipients.data?.map(row => <MenuItem key={row.id} value={row.id}>{row.values.name} · {row.values.taxId}</MenuItem>)}</TextField><Button disabled={!issuerId} variant="outlined" startIcon={<AddRounded />} onClick={() => setEditor(true)}>Cadastrar cliente</Button><TextField required label="CPF/CNPJ do tomador" value={recipient.taxId} onChange={e => setRecipient(r => ({ ...r, taxId: e.target.value.replace(/\D/g, '').slice(0, 14) }))} slotProps={{ htmlInput: { pattern: '[0-9]{11}|[0-9]{14}' } }} /><TextField required label="Nome / razão social do tomador" value={recipient.name} onChange={e => setRecipient(r => ({ ...r, name: e.target.value }))} slotProps={{ htmlInput: { maxLength: 150 } }} /><TextField type="email" label="E-mail do tomador" value={recipient.email} onChange={e => setRecipient(r => ({ ...r, email: e.target.value }))} slotProps={{ htmlInput: { maxLength: 80 } }} /><TextField label="Município da prestação (IBGE)" value={serviceMunicipality} onChange={e => setServiceMunicipality(e.target.value.replace(/\D/g, '').slice(0, 7))} helperText="Em branco: município do emitente." slotProps={{ htmlInput: { pattern: '[0-9]{7}|' } }} /></div>
        <FormControlLabel label="Informar endereço do tomador" control={<Checkbox checked={!!recipient.address} onChange={e => setRecipient(r => ({ ...r, address: e.target.checked ? { municipality: '', zip: '', street: '', number: '', neighborhood: '' } : null }))} />} />{recipient.address && <div className="field-grid">{[['municipality', 'Município IBGE', 7], ['zip', 'CEP', 8], ['street', 'Logradouro', 255], ['number', 'Número', 60], ['neighborhood', 'Bairro', 60]].map(([key, label, maxLength]) => <TextField key={key} required label={label} value={recipient.address[key]} onChange={e => setRecipient(r => ({ ...r, address: { ...r.address, [key]: e.target.value } }))} slotProps={{ htmlInput: { maxLength, ...(['municipality', 'zip'].includes(key) ? { pattern: `[0-9]{${maxLength}}` } : {}) } }} />)}</div>}
      </>}
      {step === 1 && <><Typography variant="h6" sx={{ mb: 2 }}>Serviços prestados</Typography><ErrorBox error={services.error || policy.error} onRetry={() => { services.reload(); policy.reload(); }} /><TextField select required label="Serviço / variação" value={variantName} disabled={!!items.length} onChange={e => setVariantName(e.target.value)} helperText="Itens da mesma nota compartilham códigos e tratamento fiscal. Remova os itens para trocar a variação.">{(variantName === 'Padrão' || services.data?.some(s => s.values.variantName === 'Padrão')) && <MenuItem value="Padrão">Padrão</MenuItem>}{services.data?.filter(s => s.values.variantName !== 'Padrão').map(s => <MenuItem key={s.id} value={s.values.variantName}>{s.values.variantName} · {s.values.issWithheld ? 'com' : 'sem'} retenção de ISS</MenuItem>)}</TextField><Box sx={{ display: 'flex', gap: 1, flexWrap: 'wrap', my: 2 }}><Chip size="small" label={`LC 116: ${values?.lc116 || '—'}`} /><Chip size="small" label={`Nacional: ${values?.nationalService || '—'}`} /><Chip size="small" label={`NBS: ${values?.nbs || '—'}`} /></Box><div className="field-grid"><TextField label="Quantidade" type="number" value={quantity} onChange={e => setQuantity(e.target.value)} slotProps={{ htmlInput: { min: .0001, max: 999999999, step: .0001 } }} /><TextField label="Valor unitário (R$)" type="number" value={unitPrice} onChange={e => setUnitPrice(e.target.value)} slotProps={{ htmlInput: { min: .01, max: 999999999999, step: .01 } }} /></div><Button sx={{ my: 2 }} variant="outlined" startIcon={<AddRounded />} disabled={!values || policy.loading} onClick={addItem}>Adicionar serviço</Button><TableContainer><Table size="small" aria-label="Serviços da nota"><TableHead><TableRow><TableCell>Descrição</TableCell><TableCell>Quantidade</TableCell><TableCell>Valor unitário</TableCell><TableCell>Total</TableCell><TableCell /></TableRow></TableHead><TableBody>{items.map((item, index) => <TableRow key={index}><TableCell>{resolvedDescription}</TableCell><TableCell>{item.quantity}</TableCell><TableCell>{money(item.unitPrice)}</TableCell><TableCell>{money(decimal(subtotal(item)))}</TableCell><TableCell><IconButton aria-label={`Remover serviço ${index + 1}`} onClick={() => setItems(current => current.filter((_, i) => i !== index))}><DeleteOutlineRounded /></IconButton></TableCell></TableRow>)}</TableBody></Table></TableContainer><TextField required label="Descrição" multiline minRows={3} value={resolvedDescription} onChange={e => setDescription(e.target.value)} slotProps={{ input: { readOnly: !manual }, htmlInput: { maxLength: 1600 } }} sx={{ mt: 3 }} /><FormControlLabel label="Ajustar descrição para este documento" control={<Checkbox checked={manual} onChange={e => { setManual(e.target.checked); setDescription(values?.description || ''); }} />} /><TextField label="Código tributário municipal (3 dígitos)" value={municipalServiceCode} onChange={e => setMunicipalServiceCode(e.target.value.replace(/\D/g, '').slice(0, 3))} slotProps={{ htmlInput: { pattern: '[0-9]{3}|' } }} sx={{ mt: 2 }} /></>}
      {step === 2 && <><Typography variant="h6" sx={{ mb: 2 }}>Pagamento e complementos</Typography><div className="field-grid"><TextField select label="Forma de pagamento" value={paymentMethod} onChange={e => setPaymentMethod(e.target.value)}>{payments.map(([value, label]) => <MenuItem key={value} value={value}>{label}</MenuItem>)}</TextField><TextField type="date" label="Vencimento" value={dueDate} onChange={e => setDueDate(e.target.value)} slotProps={{ inputLabel: { shrink: true } }} /><TextField select label="Tipo de desconto" value={discountType} onChange={e => setDiscountType(e.target.value)}><MenuItem value="UNCONDITIONAL">Incondicionado</MenuItem><MenuItem value="CONDITIONAL">Condicionado</MenuItem></TextField><TextField type="number" label="Desconto total (R$)" value={discount} onChange={e => setDiscount(e.target.value)} slotProps={{ htmlInput: { min: 0, max: Number(totals.gross), step: .01 } }} /></div><TextField label="Informações complementares" multiline minRows={3} value={complement} onChange={e => setComplement(e.target.value)} slotProps={{ htmlInput: { maxLength: 1700 } }} sx={{ mt: 3 }} />
        <Typography variant="h6" sx={{ mt: 4, mb: 1 }}>Retenções e deduções</Typography><Typography variant="body2" color="text.secondary" sx={{ mb: 2 }}>Padrões do emitente preenchidos automaticamente. Confirme a incidência e a base de cada retenção. Deduções especiais são tratadas no fluxo avançado de DPS.</Typography><TableContainer><Table size="small" aria-label="Retenções da nota"><TableHead><TableRow><TableCell>Reter / tributo</TableCell><TableCell>Alíquota (%)</TableCell><TableCell>Base (R$)</TableCell><TableCell>Valor retido</TableCell></TableRow></TableHead><TableBody>{taxFields.map(([key, label]) => <TableRow key={key}><TableCell><FormControlLabel label={label} control={<Checkbox checked={taxes[key].enabled} onChange={e => taxChange(key, 'enabled', e.target.checked)} />} /></TableCell><TableCell><TextField label={`Alíquota ${key.toUpperCase()} (%)`} type="number" value={taxes[key].rate} disabled={!taxes[key].enabled && key !== 'iss'} onChange={e => taxChange(key, 'rate', e.target.value)} slotProps={{ htmlInput: { min: taxes[key].enabled ? .01 : 0, max: 100, step: .01 } }} sx={{ minWidth: 115 }} /></TableCell><TableCell><TextField label={`Base ${key.toUpperCase()} (R$)`} type="number" value={taxes[key].base} disabled={!taxes[key].enabled} onChange={e => taxChange(key, 'base', e.target.value)} slotProps={{ input: { readOnly: key === 'iss' }, htmlInput: { min: 0, max: Number(totals.gross), step: .01 } }} sx={{ minWidth: 135 }} /></TableCell><TableCell sx={{ whiteSpace: 'nowrap' }}>{money(totals.taxes[key])}</TableCell></TableRow>)}</TableBody></Table></TableContainer>
        {(taxes.pis.enabled || taxes.cofins.enabled) && <TextField select label="CST PIS/COFINS" value={cst} onChange={e => setCst(e.target.value)} sx={{ mt: 2 }}>{['00', '01', '02', '04', '05', '06', '07', '08', '09', '49', '99'].map(value => <MenuItem key={value} value={value}>{value}</MenuItem>)}</TextField>}
        <ApproximateTaxes mode={approxMode} onMode={setApproxMode} values={approx} onValues={setApprox} required={false} />
        {missingSetup && <Alert severity="info" sx={{ mt: 3 }} action={<Button component={Link} to="/certificados">Configurar</Button>}>{missingSetup.message} Você pode salvar o rascunho.</Alert>}
      </>}
      {(manual || changedTaxes) && step > 0 && <TextField required label="Justificativa das alterações" value={reason} onChange={e => setReason(e.target.value)} helperText="Alterações no padrão valem somente para este documento." slotProps={{ htmlInput: { maxLength: 300 } }} sx={{ mt: 3 }} />}
      <div className="form-actions">{step > 0 && <Button color="secondary" disabled={busy} onClick={() => setStep(value => value - 1)}>Voltar</Button>}<Button type="submit" variant="contained" disabled={busy || (step === 2 && isManager && (!!missingSetup || profile.loading || proxy.loading))}>{busy ? 'Salvando…' : step === 0 ? 'Continuar para serviços' : step === 1 ? 'Continuar para pagamento' : isManager ? 'Revisar emissão' : 'Salvar rascunho'}</Button></div>
    </Paper><aside><Paper variant="outlined" className="draft-preview invoice-summary"><Typography variant="h6" sx={{ mb: 2 }}>Resumo da nota</Typography><Typography variant="body2" fontWeight={600}>{selectedIssuer?.name || 'Selecione o emitente'}</Typography><Typography variant="caption" color="text.secondary">{recipient.name || 'Tomador não informado'}</Typography><div className="preview-tax-list"><div><span>Serviços ({items.length})</span><strong>{money(totals.gross)}</strong></div><div><span>Descontos</span><strong>{money(totals.discount)}</strong></div><div><span>Valor da nota</span><strong>{money(totals.total)}</strong></div><div><span>Retenções selecionadas</span><strong>{money(totals.retained)}</strong></div><div><span>Líquido estimado</span><strong>{money(totals.net)}</strong></div></div><Typography variant="caption" color="text.secondary">Estimativa comercial com as bases e retenções selecionadas. O valor fiscal definitivo vem da autorização da NFS-e.</Typography>{values && <Box sx={{ mt: 3 }}><FieldValue label="Serviço / variação">{values.variantName}</FieldValue><FieldValue label="NBS">{values.nbs || '—'}</FieldValue><Typography variant="caption" color="text.secondary">Versão {policy.data.version} · {date(values.effectiveFrom)}</Typography></Box>}</Paper></aside></div>}
    {editor && <RecipientEditor issuerId={issuerId} initial={{ values: recipient }} onClose={() => setEditor(false)} onSaved={saved => { chooseRecipient(saved); recipients.reload(); setEditor(false); }} />}
    <Dialog open={!!confirm} fullWidth maxWidth="sm" onClose={() => !busy && setConfirm(null)}><form onSubmit={generate}><DialogTitle>Confirmar geração da nota</DialogTitle><DialogContent><ErrorBox error={error} /><Alert severity={config.data?.environment === 'PRODUCAO' ? 'warning' : 'info'} sx={{ mb: 3 }}>A nota será assinada e transmitida em <strong>{config.data?.environment === 'PRODUCAO' ? 'PRODUÇÃO' : 'HOMOLOGAÇÃO, sem validade fiscal'}</strong>.</Alert><div className="field-grid"><FieldValue label="Emitente">{selectedIssuer?.name} · {cnpj(selectedIssuer?.cnpj)}</FieldValue><FieldValue label="Tomador">{recipient.name}</FieldValue><FieldValue label="Serviços">{money(confirm?.amount)}</FieldValue><FieldValue label="Líquido estimado">{money(confirm?.snapshot.totals?.estimatedNet)}</FieldValue></div><Typography variant="body2" sx={{ my: 2 }}>Revise o documento antes de confirmar. Após a transmissão, alterações dependem das regras de cancelamento do Fisco.</Typography><TextField required label="Digite o CNPJ do emitente para confirmar" value={typed} onChange={e => setTyped(e.target.value.replace(/\D/g, '').slice(0, 14))} /></DialogContent><DialogActions sx={{ p: 3 }}><Button disabled={busy} onClick={() => setConfirm(null)}>Voltar</Button><Button type="submit" variant="contained" disabled={busy || typed !== selectedIssuer?.cnpj}>{busy ? 'Aguardando resposta…' : 'Gerar e transmitir NFS-e'}</Button></DialogActions></form></Dialog>
  </>;
}
