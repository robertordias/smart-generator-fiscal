import { MenuItem, TextField, Typography } from '@mui/material';

export default function ApproximateTaxes({ mode, onMode, values, onValues, disabled = false, required = true }) {
  return <><Typography variant="h6" sx={{ mt: 3, mb: 2 }}>Totais aproximados dos tributos</Typography>
    <TextField select required={required} disabled={disabled} label="Como informar os tributos aproximados" value={mode} onChange={e => onMode(e.target.value)}><MenuItem value="">Selecione</MenuItem><MenuItem value="SIMPLES">Percentual do Simples Nacional</MenuItem><MenuItem value="PERCENT">Percentuais federal, estadual e municipal</MenuItem><MenuItem value="NONE">Não informar, quando permitido</MenuItem></TextField>
    {mode === 'SIMPLES' && <TextField sx={{ mt: 2 }} disabled={disabled} required type="number" label="Percentual total do Simples (%)" value={values.simples} onChange={e => onValues({ ...values, simples: e.target.value })} slotProps={{ htmlInput: { min: 0, max: 100, step: .01 } }} />}
    {mode === 'PERCENT' && <div className="tax-grid" style={{ marginTop: 20 }}>{[['federal', 'Total federal (%)'], ['state', 'Total estadual (%)'], ['municipal', 'Total municipal (%)']].map(([key, label]) => <TextField key={key} required disabled={disabled} type="number" label={label} value={values[key]} onChange={e => onValues({ ...values, [key]: e.target.value })} slotProps={{ htmlInput: { min: 0, max: 100, step: .01 } }} />)}</div>}
    <Typography variant="caption" color="text.secondary" display="block" sx={{ mt: 1 }}>Informe conforme o enquadramento e a regra aplicável. Esses totais não são as retenções do serviço.</Typography>
  </>;
}
