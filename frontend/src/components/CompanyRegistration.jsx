import { Dialog, DialogContent, DialogTitle, Typography } from '@mui/material';
import { useResource } from '../context';
import { cnpj } from '../api';
import { ErrorBox, FieldValue, Loading } from './Common';

export default function CompanyRegistration({ company, onClose }) {
  const registration = useResource(`/issuers/${company.id}/registration`);
  const r = registration.data;
  return <Dialog open fullWidth maxWidth="md" onClose={onClose}><DialogTitle>Dados da empresa</DialogTitle><DialogContent>
    <Typography variant="h6">{company.name}</Typography><Typography color="text.secondary" sx={{ mb: 3 }}>{cnpj(company.cnpj)} · IBGE {company.municipality}</Typography>
    <ErrorBox error={registration.error} onRetry={registration.reload} />{registration.loading ? <Loading /> : r && <div className="field-grid">
      {Object.entries({ 'Nome fantasia': r.tradeName, 'Inscrição estadual': r.stateRegistration, 'Inscrição municipal': r.municipalRegistration, CNAE: r.cnae, 'Tributação informada no CSV': r.taxationReference, Logradouro: r.street, Número: r.number, Complemento: r.complement, CEP: r.zip, Bairro: r.neighborhood, Cidade: r.city, UF: r.uf, Telefone: r.phone, 'E-mail': r.email }).map(([label, value]) => <FieldValue key={label} label={label}>{value || '—'}</FieldValue>)}
    </div>}
  </DialogContent></Dialog>;
}
