import { useState } from 'react';
import { Alert, Button, Dialog, DialogActions, DialogContent, DialogTitle, Typography } from '@mui/material';
import { Credentials } from '../pages/FiscalCredentials';
import { useWorkspace } from '../context';
import { cnpj } from '../api';

export default function FiscalSetupDialog({ issuerId, onClose, onConfigured, focusMunicipalRegistration = false }) {
  const { companies } = useWorkspace();
  const [busy, setBusy] = useState(false);
  const issuer = companies.data?.find(c => c.id === issuerId);
  return <Dialog open fullWidth maxWidth="lg" onClose={() => !busy && onClose()} aria-labelledby="issuer-setup-title">
    <DialogTitle id="issuer-setup-title">Configuração de emissão<Typography variant="body2" color="text.secondary">{issuer?.name} · {cnpj(issuer?.cnpj)}</Typography></DialogTitle>
    <DialogContent><Alert severity="info" sx={{ mb: 3 }}>As configurações serão salvas no banco para este prestador e reutilizadas nas próximas notas. O mesmo A1 pode atender outras empresas, cada uma com seu vínculo de procuração. Seu preenchimento da nota será mantido.</Alert>
      <Credentials fixedIssuerId={issuerId} focusMunicipalRegistration={focusMunicipalRegistration} embedded onConfigured={onConfigured} onBusyChange={setBusy} />
    </DialogContent><DialogActions sx={{ p: 3 }}><Button variant="contained" disabled={busy} onClick={onClose}>Voltar à emissão</Button></DialogActions>
  </Dialog>;
}
