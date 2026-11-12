import { Alert, Box, Button, Chip, CircularProgress, Dialog, DialogContent, DialogTitle, IconButton, Paper, Typography } from '@mui/material';
import CloseRounded from '@mui/icons-material/CloseRounded';
import DescriptionOutlined from '@mui/icons-material/DescriptionOutlined';
import { date, money, rates } from '../api';
import { payments } from '../invoice';

export function PageHeading({ eyebrow = 'SEU ESCRITÓRIO, CONECTADO', title, subtitle, action }) {
  return <div className="page-heading"><div><Typography variant="overline" color="primary">{eyebrow}</Typography><Typography component="h1" variant="h4">{title}</Typography><Typography color="text.secondary" sx={{ mt: .8 }}>{subtitle}</Typography></div>{action}</div>;
}
export function Loading() { return <Box role="status" aria-label="Carregando" sx={{ p: 6, display: 'flex', justifyContent: 'center' }}><CircularProgress size={28} /></Box>; }
export function ErrorBox({ error, onRetry }) {
  return error ? <Alert severity="error" action={onRetry ? <Button color="inherit" size="small" onClick={onRetry}>Tentar novamente</Button> : null} sx={{ mb: 2 }}>{error.message}</Alert> : null;
}
export function EmptyState({ title, description, action, icon }) {
  return <div className="empty-state"><div className="empty-icon">{icon || <DescriptionOutlined />}</div><Typography variant="h6">{title}</Typography><Typography variant="body2" color="text.secondary" sx={{ maxWidth: 400, mt: .7, mb: 2 }}>{description}</Typography>{action}</div>;
}
export function DraftBadge() { return <Chip label="Rascunho" size="small" sx={{ bgcolor: '#f4f0e5', color: '#8a6a25' }} />; }
export function FieldValue({ label, children }) {
  return <div><Typography variant="body2" color="text.secondary" sx={{ fontSize: '.73rem', mb: .4 }}>{label}</Typography><Typography variant="body2" fontWeight={600} sx={{ overflowWrap: 'anywhere', whiteSpace: 'pre-wrap' }}>{children ?? '—'}</Typography></div>;
}
export function DraftDetails({ draft, onClose, issuerName }) {
  if (!draft) return null;
  const values = draft.snapshot.defaults.values;
  return <Dialog open onClose={onClose} fullWidth maxWidth="sm"><DialogTitle sx={{ pr: 6 }}>Detalhes do rascunho<IconButton aria-label="Fechar detalhes" onClick={onClose} sx={{ position: 'absolute', right: 14, top: 12 }}><CloseRounded /></IconButton></DialogTitle><DialogContent>
    <div className="detail-top"><DraftBadge /><Typography variant="h5">{money(draft.amount)}</Typography></div>
    <Alert severity="info" sx={{ mb: 3 }}>Documento de preparação. Não possui autorização nem validade fiscal.</Alert>
    <div className="field-grid"><FieldValue label="Empresa">{issuerName}</FieldValue><FieldValue label="Competência">{date(draft.competence)}</FieldValue><FieldValue label="Serviço / variação">{values.variantName || 'Padrão'}</FieldValue><FieldValue label="NBS">{values.nbs || '—'}</FieldValue><FieldValue label="Código LC 116">{values.lc116}</FieldValue><FieldValue label="Tributação nacional">{values.nationalService}</FieldValue><FieldValue label="CNAE">{values.cnae}</FieldValue><FieldValue label="Configuração aplicada">Versão {draft.snapshot.defaults.version}</FieldValue></div>
    <Box sx={{ my: 3 }}><FieldValue label="Descrição do serviço">{draft.snapshot.description}</FieldValue><Typography variant="caption" color="primary">{draft.snapshot.descriptionOrigin === 'MANUAL' ? 'Descrição ajustada manualmente' : 'Preenchida pelo padrão do emitente'}</Typography></Box>
    {draft.snapshot.overrideReason && <Box sx={{ mb: 3 }}><FieldValue label="Justificativa da alteração">{draft.snapshot.overrideReason}</FieldValue></Box>}
    {draft.snapshot.commercial && <Box sx={{ mb: 3 }}><Typography variant="h6" sx={{ mb: 2 }}>Tomador, pagamento e complementos</Typography><div className="field-grid"><FieldValue label="Tomador">{draft.snapshot.commercial.recipient.name} · {draft.snapshot.commercial.recipient.taxId}</FieldValue><FieldValue label="E-mail">{draft.snapshot.commercial.recipient.email || '—'}</FieldValue><FieldValue label="Forma de pagamento">{payments.find(([key]) => key === draft.snapshot.commercial.paymentMethod)?.[1]}</FieldValue><FieldValue label="Vencimento">{date(draft.snapshot.commercial.dueDate)}</FieldValue><FieldValue label="Descontos">{money(draft.snapshot.totals.discount)}</FieldValue><FieldValue label="Retenções selecionadas">{money(draft.snapshot.totals.retained)}</FieldValue><FieldValue label="Líquido estimado">{money(draft.snapshot.totals.estimatedNet)}</FieldValue></div><Box sx={{ mt: 2 }}><FieldValue label="Informações complementares">{draft.snapshot.commercial.complement || '—'}</FieldValue></Box></Box>}
    {draft.snapshot.sourceDefaults && <Alert severity="info" sx={{ mb: 2 }}>Retenções ajustadas somente neste documento. Os padrões do emitente foram preservados.</Alert>}
    <Typography variant="subtitle2" sx={{ mb: 1.5 }}>Percentuais da configuração</Typography><div className="tax-grid">{rates.map(([key, label]) => <FieldValue key={key} label={label}>{values[key]}%</FieldValue>)}</div>
    <Typography variant="body2" color="text.secondary" sx={{ mt: 2 }}>ISS retido: {values.issWithheld ? 'Sim' : 'Não'}. {draft.snapshot.commercial ? 'Estimativas calculadas com as bases e retenções selecionadas no formulário.' : 'Percentuais armazenados como referência, sem cálculo tributário.'}</Typography>
    <Paper variant="outlined" sx={{ p: 2, mt: 3 }}><FieldValue label="Identificador">{draft.id}</FieldValue><Typography variant="caption" color="text.secondary">Registrado por {draft.snapshot.actor} · Configuração preservada no documento.</Typography></Paper>
  </DialogContent></Dialog>;
}
