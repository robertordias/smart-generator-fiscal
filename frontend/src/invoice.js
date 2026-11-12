export const taxFields = [
  ['iss', 'ISS retido pelo tomador', 'issRate'], ['pis', 'PIS', 'pisRate'], ['cofins', 'COFINS', 'cofinsRate'],
  ['csll', 'CSLL', 'csllRate'], ['irrf', 'IRRF', 'irrfRate'], ['inss', 'INSS (contribuição previdenciária)', 'inssRate'],
];
export const payments = [['NONE', 'Não informado'], ['PIX', 'PIX'], ['CASH', 'Dinheiro'], ['TRANSFER', 'Transferência bancária'], ['CREDIT', 'Cartão de crédito'], ['DEBIT', 'Cartão de débito'], ['OTHER', 'Outros']];
export function scaled(value, digits = 2) {
  const text = String(value ?? '').trim();
  if (!/^\d+(?:\.\d*)?$/.test(text)) return 0n;
  const [integer, fraction = ''] = text.split('.');
  if (fraction.length > digits) return 0n;
  return BigInt(integer) * 10n ** BigInt(digits) + BigInt(fraction.padEnd(digits, '0') || '0');
}
export function decimal(cents) { const sign = cents < 0n ? '-' : ''; const absolute = cents < 0n ? -cents : cents; return `${sign}${absolute / 100n}.${String(absolute % 100n).padStart(2, '0')}`; }
export function subtotal(item) { return (scaled(item.quantity, 4) * scaled(item.unitPrice) + 5000n) / 10000n; }
export function quote(items, unconditional, conditional, taxes) {
  const gross = items.reduce((total, item) => total + subtotal(item), 0n);
  const discount = scaled(unconditional) + scaled(conditional);
  const retained = Object.fromEntries(taxFields.map(([key]) => {
    const tax = taxes[key]; return [key, tax?.enabled ? (scaled(tax.base) * scaled(tax.rate) + 5000n) / 10000n : 0n];
  }));
  const totalRetained = Object.values(retained).reduce((total, value) => total + value, 0n);
  return { gross: decimal(gross), discount: decimal(discount), total: decimal(gross - discount), retained: decimal(totalRetained), net: decimal(gross - discount - totalRetained), issBase: decimal(gross - scaled(unconditional)), taxes: Object.fromEntries(Object.entries(retained).map(([key, value]) => [key, decimal(value)])) };
}
export function defaultTaxes(policy = {}, base = '0.00') {
  return Object.fromEntries(taxFields.map(([key, , rate]) => [key, { enabled: key === 'iss' ? !!policy.issWithheld : Number(policy[rate]) > 0, rate: String(policy[rate] || 0), base, manualBase: false }]));
}
