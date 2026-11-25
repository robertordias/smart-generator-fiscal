import { useEffect, useState } from 'react';
import { Accordion, AccordionDetails, AccordionSummary, Alert, Box, Button, Checkbox, Dialog, DialogActions, DialogContent, DialogTitle, FormControlLabel, MenuItem, TextField, Typography } from '@mui/material';
import ExpandMoreRounded from '@mui/icons-material/ExpandMoreRounded';
import { Link } from 'react-router-dom';
import { useAuth, useResource, useWorkspace } from '../context';
import { date, money, query } from '../api';
import { ErrorBox, FieldValue, Loading } from './Common';
import RecipientEditor from './RecipientEditor';
import ApproximateTaxes from './ApproximateTaxes';
import { payments } from '../invoice';

const ns = 'http://www.sped.fazenda.gov.br/nfse';
const escape = value => String(value).replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&apos;' })[c]);
const tag = (name, value) => `<${name}>${escape(value)}</${name}>`;
export default function PrepareNfse({ initialDraftId = '', onClose, onPrepared }) {
  const { api } = useAuth();
  const { issuerId, companies } = useWorkspace();
  const [draftId, setDraftId] = useState(initialDraftId);
  const drafts = useResource(`/drafts?${query({ issuerId, size: 100 })}`);
  const selected = useResource(draftId ? `/drafts/${draftId}` : null);
  const recipients = useResource(selected.data ? `/issuers/${selected.data.issuerId}/recipients` : null);
  const [recipientId, setRecipientId] = useState('');
  const [recipientEditor, setRecipientEditor] = useState(false);
  const grant = useResource(selected.data ? `/nfse/issuers/${selected.data.issuerId}/proxy` : null);
  const profile = useResource(selected.data ? `/nfse/issuers/${selected.data.issuerId}/profile` : null);
  const [form, setForm] = useState({ taxId: '', name: '', email: '', serviceMunicipality: '', municipalServiceCode: '', nbs: '', advancedDpsXml: '', ibsCbsXml: '', totalTaxesXml: '', federalTaxesXml: '' });
  const [address, setAddress] = useState(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState(null);
  const [review, setReview] = useState(false);
  const [federal, setFederal] = useState(false);
  const [totalsMode, setTotalsMode] = useState('');
  const [totals, setTotals] = useState({ simples: '', federal: '', state: '', municipal: '' });
  const [federalValues, setFederalValues] = useState({ irrf: '', csll: '', cp: '', pisEnabled: false, cst: '01', base: '', pis: '', cofins: '', pisRate: '', cofinsRate: '', retained: '1' });
  const change = (key, value) => { setForm(f => ({ ...f, [key]: value })); setReview(false); };
  const draft = selected.data;
  const defaults = draft?.snapshot.defaults.values;
  const commercial = draft?.snapshot.commercial;
  useEffect(() => {
    setForm(f => ({ ...f, taxId: commercial?.recipient.taxId || '', name: commercial?.recipient.name || '', email: commercial?.recipient.email || '', nbs: defaults?.nbs || '', serviceMunicipality: commercial?.serviceMunicipality || '', municipalServiceCode: commercial?.municipalServiceCode || '' }));
    setAddress(commercial?.recipient.address || null);
    setTotalsMode(commercial?.approximation?.mode || '');
    setTotals({ simples: commercial?.approximation?.simples ?? '', federal: commercial?.approximation?.federal ?? '', state: commercial?.approximation?.state ?? '', municipal: commercial?.approximation?.municipal ?? '' }); setRecipientId(''); setReview(false); setRecipientEditor(false);
  }, [draft?.id]);
  function useRecipient(saved) {
    setRecipientId(saved?.id || '');
    const values = saved?.values || {};
    setForm(f => ({ ...f, taxId: values.taxId || '', name: values.name || '', email: values.email || '' }));
    setAddress(values.address ? { ...values.address } : saved ? { municipality: '', zip: '', street: '', number: '', neighborhood: '' } : null); setReview(false);
  }
  const expectedFederal = defaults && ['irrfRate', 'pisRate', 'cofinsRate', 'csllRate', 'inssRate'].some(key => Number(defaults[key]) > 0);
  const missingSetup = grant.error || profile.error;
  async function submit(event) {
    event.preventDefault(); setError(null);
    if (defaults?.issWithheld && !address && !form.advancedDpsXml) { setError(new Error(commercial ? 'Este rascunho não possui endereço do tomador e tem ISS retido. Corrija o cadastro e crie um novo rascunho.' : 'Informe o endereço completo do tomador para emitir com ISS retido.')); return; }
    if (!review) { setReview(true); return; }
    setBusy(true);
    try {
      let federalXml = form.federalTaxesXml;
      if (federal) {
        federalXml = `<tribFed xmlns="${ns}">`;
        if (federalValues.pisEnabled) {
          federalXml += `<piscofins>${tag('CST', federalValues.cst)}${tag('vBCPisCofins', federalValues.base)}${tag('pAliqPis', federalValues.pisRate)}${tag('pAliqCofins', federalValues.cofinsRate)}${tag('vPis', federalValues.pis)}${tag('vCofins', federalValues.cofins)}${tag('tpRetPisCofins', federalValues.retained)}</piscofins>`;
        }
        if (federalValues.cp !== '') federalXml += tag('vRetCP', federalValues.cp);
        if (federalValues.irrf !== '') federalXml += tag('vRetIRRF', federalValues.irrf);
        if (federalValues.csll !== '') federalXml += tag('vRetCSLL', federalValues.csll);
        federalXml += '</tribFed>';
      }
      let totalsXml = form.totalTaxesXml;
      if (!totalsXml && totalsMode) {
        const content = totalsMode === 'NONE' ? tag('indTotTrib', 0) : totalsMode === 'SIMPLES' ? tag('pTotTribSN', totals.simples) : `<pTotTrib>${tag('pTotTribFed', totals.federal)}${tag('pTotTribEst', totals.state)}${tag('pTotTribMun', totals.municipal)}</pTotTrib>`;
        totalsXml = `<totTrib xmlns="${ns}">${content}</totTrib>`;
      }
      const result = await api('/nfse/prepare', { method: 'POST', body: {
        draftId, recipient: form.advancedDpsXml ? null : { taxId: form.taxId, name: form.name, email: form.email || null, address },
        serviceMunicipality: form.serviceMunicipality || '', municipalServiceCode: form.municipalServiceCode || '', nbs: form.nbs || '',
        advancedDpsXml: form.advancedDpsXml || null, federalTaxesXml: federalXml || null, totalTaxesXml: totalsXml || null, ibsCbsXml: form.ibsCbsXml || null,
      } });
      onPrepared(result);
    } catch (e) { setError(e); } finally { setBusy(false); }
  }
  const fed = (key, value) => { setFederalValues(v => ({ ...v, [key]: value })); setReview(false); };
  return <><Dialog open onClose={() => !busy && onClose()} fullWidth maxWidth="md"><form onSubmit={submit}><DialogTitle>Preparar emissão nacional<Typography variant="body2" color="text.secondary">DPS do cliente, assinada com o A1 do contador</Typography></DialogTitle><DialogContent>
    <ErrorBox error={error || drafts.error || selected.error} />
    {review ? <><Alert severity="info" sx={{ mb: 3 }}>Esta etapa assina e reserva a numeração da DPS. A transmissão será confirmada separadamente.</Alert><div className="field-grid"><FieldValue label="Empresa">{companies.data?.find(c => c.id === draft?.issuerId)?.name}</FieldValue><FieldValue label="Competência">{date(draft?.competence)}</FieldValue><FieldValue label="Valor">{money(draft?.amount)}</FieldValue><FieldValue label="NBS">{defaults?.nbs || form.nbs || '—'}</FieldValue><FieldValue label="Serviço / variação">{defaults?.variantName || 'Padrão'}</FieldValue><FieldValue label="ISS retido">{defaults?.issWithheld ? 'Sim' : 'Não'}</FieldValue><FieldValue label="Padrão fiscal">Versão {draft?.snapshot.defaults.version}</FieldValue><FieldValue label="Tomador">{form.advancedDpsXml ? 'Identificado na DPS completa importada' : `${form.name} · ${form.taxId}`}</FieldValue><FieldValue label="Procuração declarada">{grant.data?.reference}</FieldValue><FieldValue label="Série">{profile.data?.series}</FieldValue><FieldValue label="Situação no Simples">{{ 1: 'Não optante', 2: 'MEI', 3: 'ME/EPP' }[profile.data?.simplesOption]}</FieldValue></div><Box sx={{ mt: 3 }}><FieldValue label="Descrição preservada do rascunho">{draft?.snapshot.description}</FieldValue></Box></> : <>
      <TextField select required label="Rascunho de origem" value={draftId} onChange={e => { setDraftId(e.target.value); setError(null); setReview(false); }} sx={{ mt: 1, mb: 3 }}><MenuItem value="">Selecione um rascunho</MenuItem>{draftId && !drafts.data?.items.some(d => d.id === draftId) && <MenuItem value={draftId}>{draft ? `${date(draft.competence)} · ${money(draft.amount)}` : draftId}</MenuItem>}{drafts.data?.items.map(d => <MenuItem key={d.id} value={d.id}>{companies.data?.find(c => c.id === d.issuerId)?.name} · {date(d.competence)} · {money(d.amount)}</MenuItem>)}</TextField>
      {selected.loading && draftId ? <Loading /> : draft && <>
        <Alert severity="info" sx={{ mb: 3 }}>{date(draft.competence)} · {money(draft.amount)} · padrão fiscal versão {draft.snapshot.defaults.version}. Códigos, descrição e valor serão preservados.</Alert>
        {missingSetup && <Alert severity="warning" sx={{ mb: 3 }} action={<Button component={Link} to="/certificados" onClick={onClose}>Configurar</Button>}>{missingSetup.message}</Alert>}
        {commercial && <><Typography variant="h6" sx={{ mb: 2 }}>Dados do formulário preservados</Typography><div className="field-grid"><FieldValue label="Tomador">{commercial.recipient.name} · {commercial.recipient.taxId}</FieldValue><FieldValue label="NBS">{defaults.nbs || '—'}</FieldValue><FieldValue label="E-mail do tomador">{commercial.recipient.email || '—'}</FieldValue>{commercial.recipient.address && <FieldValue label="Endereço do tomador">{commercial.recipient.address.street}, {commercial.recipient.address.number} · {commercial.recipient.address.zip}</FieldValue>}<FieldValue label="Pagamento">{payments.find(([key]) => key === commercial.paymentMethod)?.[1]}</FieldValue><FieldValue label="Descontos">{money(draft.snapshot.totals.discount)}</FieldValue><FieldValue label="Retenções selecionadas">{money(draft.snapshot.totals.retained)}</FieldValue><FieldValue label="Líquido estimado">{money(draft.snapshot.totals.estimatedNet)}</FieldValue></div><Typography variant="body2" sx={{ mt: 2, whiteSpace: 'pre-wrap' }}>{commercial.complement}</Typography>{!commercial.approximation && <ApproximateTaxes mode={totalsMode} onMode={value => { setTotalsMode(value); setReview(false); }} values={totals} onValues={setTotals} />}</>}
        {!commercial && !form.advancedDpsXml && <><Typography variant="h6" sx={{ mb: 2 }}>Tomador do serviço</Typography><ErrorBox error={recipients.error} onRetry={recipients.reload} /><TextField select label="Tomador cadastrado" value={recipientId} onChange={e => useRecipient(recipients.data?.find(row => row.id === e.target.value))} sx={{ mb: 2 }}><MenuItem value="">Informar manualmente</MenuItem>{recipients.data?.map(row => <MenuItem key={row.id} value={row.id}>{row.values.name} · {row.values.taxId}</MenuItem>)}</TextField><Button variant="outlined" onClick={() => setRecipientEditor(true)} sx={{ mb: 2 }}>Salvar tomador no cadastro</Button><div className="field-grid"><TextField required label="CPF/CNPJ do tomador" value={form.taxId} onChange={e => change('taxId', e.target.value.replace(/\D/g, '').slice(0, 14))} slotProps={{ htmlInput: { pattern: '[0-9]{11}|[0-9]{14}', inputMode: 'numeric' } }} /><TextField required label="Nome / razão social do tomador" value={form.name} onChange={e => change('name', e.target.value)} slotProps={{ htmlInput: { maxLength: 150 } }} /><TextField type="email" label="E-mail do tomador" value={form.email} onChange={e => change('email', e.target.value)} /><TextField label="Município da prestação (IBGE)" value={form.serviceMunicipality} onChange={e => change('serviceMunicipality', e.target.value)} helperText="Em branco: município do emitente." slotProps={{ htmlInput: { pattern: '[0-9]{7}|', maxLength: 7, inputMode: 'numeric' } }} /></div><FormControlLabel sx={{ my: 2 }} label="Informar endereço do tomador" control={<Checkbox checked={!!address} onChange={e => setAddress(e.target.checked ? { municipality: '', zip: '', street: '', number: '', neighborhood: '' } : null)} />} />{address && <div className="field-grid">{[['municipality', 'Município IBGE', '[0-9]{7}'], ['zip', 'CEP', '[0-9]{8}'], ['street', 'Logradouro'], ['number', 'Número'], ['neighborhood', 'Bairro']].map(([key, label, pattern]) => <TextField required key={key} label={label} value={address[key]} onChange={e => setAddress(a => ({ ...a, [key]: e.target.value }))} slotProps={{ htmlInput: { pattern, maxLength: key === 'street' ? 255 : 60 } }} />)}</div>}
        <Typography variant="h6" sx={{ mt: 3, mb: 2 }}>Classificação do serviço</Typography><div className="field-grid"><TextField label="Código tributário municipal (3 dígitos)" value={form.municipalServiceCode} onChange={e => change('municipalServiceCode', e.target.value)} slotProps={{ htmlInput: { pattern: '[0-9]{3}|', maxLength: 3 } }} /><TextField label="Código NBS (9 dígitos)" value={form.nbs} helperText={defaults?.nbs ? 'NBS preservado no padrão fiscal do rascunho.' : 'Informe quando aplicável ao serviço.'} onChange={e => change('nbs', e.target.value)} slotProps={{ input: { readOnly: !!defaults?.nbs }, htmlInput: { pattern: '[0-9]{9}|', maxLength: 9 } }} /></div>
        <ApproximateTaxes mode={totalsMode} onMode={value => { setTotalsMode(value); setReview(false); }} values={totals} onValues={setTotals} disabled={!!form.totalTaxesXml} required={!form.totalTaxesXml} />
        <FormControlLabel sx={{ mt: 2 }} label="Informar tributos federais / retenções" control={<Checkbox checked={federal} onChange={e => { setFederal(e.target.checked); setReview(false); }} />} />{expectedFederal && !federal && !form.federalTaxesXml && <Alert severity="warning" sx={{ mt: 1 }}>O padrão do emitente contém percentuais federais. Confirme seus valores ou importe uma DPS completa; eles não serão omitidos automaticamente.</Alert>}
        {federal && <Box sx={{ my: 2 }}><Typography variant="body2" color="text.secondary" sx={{ mb: 2 }}>Confirme a incidência e a base de cada tributo. Percentuais do padrão: IRRF {defaults.irrfRate}%, PIS {defaults.pisRate}%, COFINS {defaults.cofinsRate}%, CSLL {defaults.csllRate}%, INSS {defaults.inssRate}%. Os valores não são calculados automaticamente.</Typography><div className="tax-grid">{[['irrf', 'IRRF retido (R$)', 'irrfRate'], ['csll', 'CSLL retida (R$)', 'csllRate'], ['cp', 'Contribuição previdenciária retida (R$)', 'inssRate']].map(([key, label, rate]) => <TextField type="number" required={Number(defaults[rate]) > 0} label={label} key={key} value={federalValues[key]} onChange={e => fed(key, e.target.value)} slotProps={{ htmlInput: { min: 0, step: .01 } }} />)}</div><FormControlLabel control={<Checkbox checked={federalValues.pisEnabled} onChange={e => fed('pisEnabled', e.target.checked)} />} label="Informar PIS/COFINS" />{federalValues.pisEnabled && <div className="tax-grid"><TextField select label="CST PIS/COFINS" value={federalValues.cst} onChange={e => fed('cst', e.target.value)}>{['00', '01', '02', '04', '05', '06', '07', '08', '09', '49', '99'].map(c => <MenuItem key={c} value={c}>{c}</MenuItem>)}</TextField>{[['base', 'Base PIS/COFINS (R$)'], ['pisRate', 'Alíquota PIS (%)'], ['cofinsRate', 'Alíquota COFINS (%)'], ['pis', 'Valor PIS (R$)'], ['cofins', 'Valor COFINS (R$)']].map(([key, label]) => <TextField key={key} required type="number" label={label} value={federalValues[key]} onChange={e => fed(key, e.target.value)} slotProps={{ htmlInput: { min: 0, step: .01 } }} />)}<TextField select label="Retenção PIS/COFINS" value={federalValues.retained} onChange={e => fed('retained', e.target.value)}><MenuItem value="1">Retidos</MenuItem><MenuItem value="2">Não retidos</MenuItem></TextField></div>}</Box>}</>}
        {!commercial && <Accordion disableGutters elevation={0} sx={{ mt: 3, border: '1px solid', borderColor: 'divider' }}><AccordionSummary expandIcon={<ExpandMoreRounded />}><Typography variant="subtitle2">Dados complementares e operações especiais</Typography></AccordionSummary><AccordionDetails><Typography variant="body2" color="text.secondary" sx={{ mb: 3 }}>Para IBS/CBS, totais aproximados, exportação, imunidade, deduções e outros cenários, informe os grupos oficiais ou uma DPS completa não assinada. Todos serão validados pelo XSD. A identificação, numeração e assinatura serão geradas pelo sistema.</Typography><div className="form-stack"><TextField multiline minRows={3} label="Grupo IBS/CBS (XML oficial, quando exigido)" value={form.ibsCbsXml} onChange={e => change('ibsCbsXml', e.target.value)} /><TextField multiline minRows={3} label="Totais aproximados dos tributos (grupo totTrib)" value={form.totalTaxesXml} onChange={e => change('totalTaxesXml', e.target.value)} helperText="Alternativa ao formulário de totais. Confirme a regra aplicável ao emitente." /><TextField multiline minRows={3} label="Tributos federais em XML (alternativa ao formulário)" value={form.federalTaxesXml} onChange={e => change('federalTaxesXml', e.target.value)} disabled={federal} /><TextField multiline minRows={5} label="DPS completa não assinada (opcional)" value={form.advancedDpsXml} onChange={e => change('advancedDpsXml', e.target.value)} helperText="Deve corresponder ao emitente, competência, valor, descrição e código nacional do rascunho." /></div></AccordionDetails></Accordion>}
      </>}
    </>}
  </DialogContent><DialogActions sx={{ p: 3 }}><Button color="secondary" disabled={busy} onClick={onClose}>Cancelar</Button>{review && <Button disabled={busy} onClick={() => setReview(false)}>Voltar</Button>}<Button variant="contained" type="submit" disabled={busy || !draft || !!missingSetup || grant.loading || profile.loading}>{busy ? 'Validando e assinando…' : review ? 'Validar e assinar DPS' : 'Revisar emissão'}</Button></DialogActions></form></Dialog>{recipientEditor && draft && <RecipientEditor issuerId={draft.issuerId} initial={{ id: recipientId || undefined, values: { taxId: form.taxId, name: form.name, email: form.email, address } }} onClose={() => setRecipientEditor(false)} onSaved={saved => { setRecipientEditor(false); recipients.reload(); useRecipient(saved); }} />}</>;
}
