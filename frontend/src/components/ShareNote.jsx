import { useState } from 'react';
import { Alert, Button, Dialog, DialogActions, DialogContent, DialogTitle, Stack, TextField, Typography } from '@mui/material';
import WhatsApp from '@mui/icons-material/WhatsApp';
import EmailOutlined from '@mui/icons-material/EmailOutlined';
import { useAuth, useResource } from '../context';
import { date, money } from '../api';
import { ErrorBox, Loading } from './Common';

export default function ShareNote({ note, company, onClose }) {
  const { api } = useAuth();
  const sharing = useResource(`/nfse/notes/${note.id}/sharing`);
  const [phone, setPhone] = useState('');
  const [email, setEmail] = useState(null);
  const [error, setError] = useState(null);
  const [notice, setNotice] = useState('');
  const [busy, setBusy] = useState(false);
  const destination = email ?? sharing.data?.email ?? '';
  const isCancelled = note.status === 'CANCELLED';
  const message = `NFS-e ${note.officialNumber || ''} · ${company?.name || 'SA Trindade'}\n${note.environment === 'HOMOLOGACAO' ? 'HOMOLOGAÇÃO — sem validade fiscal\n' : ''}${isCancelled ? 'NOTA CANCELADA\n' : ''}Competência: ${date(note.competence)}\nValor: ${money(note.amount)}\nConsulta e DANFSe: ${sharing.data?.consultationUrl || ''}`;
  const digits = phone.replace(/\D/g, '');
  const number = digits.length === 10 || digits.length === 11 ? `55${digits}` : digits;
  async function xml(share = false) {
    setBusy(true); setError(null); setNotice('');
    try {
      const blob = await api(`/nfse/notes/${note.id}/files/xml`, { responseType: 'blob' });
      const filename = `nfse-${note.officialNumber || note.id}.xml`;
      const file = new File([blob], filename, { type: 'application/xml' });
      if (share && navigator.canShare?.({ files: [file] })) { await navigator.share({ title: 'NFS-e · SA Trindade', text: message, files: [file] }); return; }
      const url = URL.createObjectURL(blob); const anchor = document.createElement('a'); anchor.href = url; anchor.download = filename; anchor.click(); setTimeout(() => URL.revokeObjectURL(url), 1000);
      if (share) setNotice('O navegador não oferece compartilhamento de arquivos. XML baixado para anexar manualmente.');
    } catch (e) { if (e.name !== 'AbortError') setError(e); } finally { setBusy(false); }
  }
  async function copy() {
    try { await navigator.clipboard.writeText(message); setNotice('Mensagem com o link da consulta copiada.'); }
    catch { setError(new Error('Não foi possível copiar. Selecione o texto da mensagem e copie manualmente.')); }
  }
  return <Dialog open fullWidth maxWidth="sm" onClose={() => !busy && onClose()}><DialogTitle>Compartilhar nota fiscal</DialogTitle><DialogContent>
    <ErrorBox error={error || sharing.error} />{sharing.loading ? <Loading /> : sharing.data && <>
      <Alert severity={isCancelled ? 'warning' : 'info'} sx={{ mb: 2 }}>{isCancelled ? 'Esta nota está cancelada. Essa informação acompanhará a mensagem.' : 'WhatsApp e e-mail serão abertos com a mensagem pronta. Você confirma o envio no aplicativo escolhido.'}</Alert>
      <Typography variant="body2" sx={{ mb: 2 }}>Tomador: {sharing.data.recipientName || 'Informado na nota'}</Typography>
      <Stack gap={2}><TextField label="WhatsApp do destinatário" value={phone} onChange={e => setPhone(e.target.value.replace(/[^0-9+() -]/g, '').slice(0, 22))} helperText="Informe DDD e número. O código 55 é incluído para números brasileiros." />
        <Button component="a" startIcon={<WhatsApp />} href={`https://wa.me/${number}?text=${encodeURIComponent(message)}`} target="_blank" rel="noopener noreferrer" variant="outlined" disabled={number.length < 12 || number.length > 15}>Abrir WhatsApp com link</Button>
        <TextField label="E-mail do destinatário" type="email" value={destination} onChange={e => setEmail(e.target.value)} />
        <Button component="a" startIcon={<EmailOutlined />} href={`mailto:${encodeURIComponent(destination)}?subject=${encodeURIComponent(`NFS-e ${note.officialNumber || ''} · ${company?.name || 'SA Trindade'}`)}&body=${encodeURIComponent(message)}`} variant="outlined" disabled={!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(destination)}>Abrir e-mail com link</Button>
        <TextField label="Mensagem para compartilhar" multiline minRows={5} value={message} slotProps={{ input: { readOnly: true } }} />
      </Stack>
      <Typography variant="caption" color="text.secondary" display="block" sx={{ mt: 2 }}>O link abre a consulta oficial, onde é possível obter o DANFSe. Para anexos, baixe o XML e o PDF da consulta ou use o compartilhamento de XML quando suportado pelo aparelho.</Typography>
      {notice && <Alert severity="info" sx={{ mt: 2 }}>{notice}</Alert>}
    </>}
  </DialogContent><DialogActions sx={{ p: 3, flexWrap: 'wrap' }}><Button disabled={busy} onClick={onClose}>Fechar</Button><Button disabled={busy || !sharing.data} onClick={copy}>Copiar mensagem</Button><Button disabled={busy || !sharing.data} onClick={() => xml(false)}>Baixar XML</Button><Button variant="contained" disabled={busy || !sharing.data} onClick={() => xml(true)}>Compartilhar XML</Button></DialogActions></Dialog>;
}
