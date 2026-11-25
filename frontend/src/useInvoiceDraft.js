import { useEffect, useRef, useState } from 'react';
import { today } from './api';
import { defaultTaxes } from './invoice';

export const invoiceStoragePrefix = user => `sa-trindade:invoice:v1:${encodeURIComponent(user.tenantId)}:${encodeURIComponent(user.username)}:`;
export const blankInvoice = () => ({
  step: 0, competence: today(), variantName: 'Padrão', recipientId: '', recipient: { taxId: '', name: '', email: '', address: null },
  items: [], quantity: '1', unitPrice: '', manual: false, description: '', reason: '', paymentMethod: 'NONE', dueDate: '', discount: '0', discountType: 'UNCONDITIONAL',
  complement: '', serviceMunicipality: '', useIssuerMunicipality: true, municipalServiceCode: '', taxes: defaultTaxes(), cst: '01', approxMode: '',
  approx: { simples: '', federal: '', state: '', municipal: '' }, savedAttempt: null, pendingEmission: null,
});
function read(key) {
  try {
    const text = localStorage.getItem(key);
    if (!text) return null;
    const record = JSON.parse(text), value = record.form;
    if (record.version !== 1 || !value || typeof value !== 'object') return null;
    const defaults = blankInvoice();
    for (const [name, fallback] of Object.entries(defaults)) {
      if (['recipient', 'items', 'taxes', 'approx', 'savedAttempt', 'pendingEmission'].includes(name)) continue;
      if (typeof value[name] !== typeof fallback) return null;
    }
    if (!Number.isInteger(value.step) || value.step < 0 || value.step > 2 || !Array.isArray(value.items) || value.items.length > 20) return null;
    const numeric = n => ['string', 'number'].includes(typeof n) && String(n).length <= 40;
    if (!value.items.every(item => item && numeric(item.quantity) && numeric(item.unitPrice))) return null;
    if (!value.recipient || !['taxId', 'name', 'email'].every(k => typeof value.recipient[k] === 'string')) return null;
    if (value.recipient.address !== null && (!value.recipient.address || !['municipality', 'zip', 'street', 'number', 'neighborhood'].every(k => typeof value.recipient.address[k] === 'string'))) return null;
    if (!value.taxes || !Object.keys(defaults.taxes).every(k => value.taxes[k] && typeof value.taxes[k].enabled === 'boolean' && numeric(value.taxes[k].rate) && numeric(value.taxes[k].base))) return null;
    if (!value.approx || !Object.keys(defaults.approx).every(k => numeric(value.approx[k]))) return null;
    if (value.savedAttempt && (typeof value.savedAttempt.body !== 'string' || typeof value.savedAttempt.key !== 'string')) return null;
    if (value.pendingEmission && !['noteId', 'draftId'].every(k => typeof value.pendingEmission[k] === 'string')) return null;
    return Object.fromEntries(Object.keys(defaults).map(k => [k, value[k] ?? defaults[k]]));
  } catch { return null; }
}
export default function useInvoiceDraft(key) {
  const [initial] = useState(() => read(key));
  const [form, setForm] = useState(() => initial || blankInvoice());
  const [storageError, setStorageError] = useState('');
  const [restored, setRestored] = useState(!!initial);
  const dirty = useRef(!!initial), latest = useRef(form);
  latest.current = form;
  function write(value) {
    try { localStorage.setItem(key, JSON.stringify({ version: 1, updatedAt: new Date().toISOString(), form: value })); setStorageError(''); }
    catch { setStorageError('Não foi possível salvar o preenchimento neste navegador. Mantenha esta página aberta ou salve um rascunho antes de sair.'); }
  }
  useEffect(() => { if (dirty.current) write(form); }, [form, key]);
  function field(name) {
    return [form[name], value => { dirty.current = true; setForm(current => ({ ...current, [name]: typeof value === 'function' ? value(current[name]) : value })); }];
  }
  function automatic(name, value) { setForm(current => ({ ...current, [name]: typeof value === 'function' ? value(current[name]) : value })); }
  function commit(patch) {
    dirty.current = true;
    const next = { ...latest.current, ...patch }; latest.current = next; write(next); setForm(next);
  }
  function clear() {
    dirty.current = false; setRestored(false);
    try { localStorage.removeItem(key); } catch { setStorageError('Não foi possível apagar o preenchimento local neste navegador.'); }
  }
  function reset(policy) { clear(); setForm({ ...blankInvoice(), taxes: defaultTaxes(policy) }); }
  return { form, field, automatic, commit, clear, reset, restored, storageError };
}
