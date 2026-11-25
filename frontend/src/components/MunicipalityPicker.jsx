import { useEffect, useRef, useState } from 'react';
import { Autocomplete, createFilterOptions, TextField } from '@mui/material';
import { useResource } from '../context';

const filterOptions = createFilterOptions({ limit: 50, stringify: city => `${city.name} ${city.uf} ${city.id}` });
export default function MunicipalityPicker({ value, onChange, required = false, disabled = false }) {
  const catalog = useResource('/municipalities');
  const [input, setInput] = useState('');
  const inputRef = useRef(null);
  const selected = catalog.data?.find(city => city.id === value) || null;
  const invalid = !disabled && ((!!input.trim() && !selected) || (required && !selected));
  useEffect(() => {
    inputRef.current?.setCustomValidity(invalid ? 'Selecione um município na lista.' : '');
  }, [invalid]);
  return <Autocomplete disabled={disabled} options={catalog.data || []} value={selected} inputValue={input} loading={catalog.loading}
    filterOptions={filterOptions} getOptionLabel={city => `${city.name} / ${city.uf}`} isOptionEqualToValue={(a, b) => a.id === b.id}
    clearOnBlur={false} noOptionsText={catalog.error ? 'Não foi possível carregar as cidades. Atualize a página.' : 'Nenhum município encontrado'} loadingText="Carregando municípios…"
    onChange={(_, city) => onChange(city?.id || '')}
    onInputChange={(_, text, reason) => { setInput(text); if (reason === 'input' || reason === 'clear') onChange(''); }}
    renderInput={params => <TextField {...params} required={required} inputRef={inputRef} label="Município da prestação" placeholder="Digite a cidade ou UF" error={!!catalog.error || invalid}
      helperText={catalog.error ? 'Não foi possível carregar o catálogo. Atualize a página para tentar novamente.' : invalid ? 'Selecione uma cidade da lista.' : selected ? `Código IBGE: ${selected.id}` : required ? 'Selecione o município onde o serviço foi prestado.' : 'Município do prestador.'} />} />;
}
