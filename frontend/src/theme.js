import { createTheme } from '@mui/material/styles';

export const theme = createTheme({
  palette: {
    primary: { main: '#927027', dark: '#70531b', light: '#c4a45c', contrastText: '#ffffff' },
    secondary: { main: '#41464e' },
    background: { default: '#f6f7f9', paper: '#ffffff' },
    text: { primary: '#252a32', secondary: '#737984' },
    divider: '#eceef1',
    success: { main: '#3e7863' },
  },
  typography: {
    fontFamily: '"Segoe UI", -apple-system, BlinkMacSystemFont, Arial, sans-serif',
    h4: { fontSize: '1.85rem', fontWeight: 650, letterSpacing: '-1px' },
    h5: { fontSize: '1.35rem', fontWeight: 650, letterSpacing: '-0.5px' },
    h6: { fontSize: '1.05rem', fontWeight: 650 },
    body1: { fontSize: '.9rem' }, body2: { fontSize: '.83rem', lineHeight: 1.65 },
    button: { textTransform: 'none', fontWeight: 600, fontSize: '.85rem' },
    overline: { fontSize: '.64rem', fontWeight: 700, letterSpacing: '1.6px' },
  },
  shape: { borderRadius: 10 },
  components: {
    MuiButton: { defaultProps: { disableElevation: true }, styleOverrides: { root: { borderRadius: 7, padding: '9px 17px' } } },
    MuiPaper: { defaultProps: { elevation: 0 }, styleOverrides: { outlined: { borderColor: '#e8eaee' } } },
    MuiTextField: { defaultProps: { size: 'small', fullWidth: true, variant: 'outlined' } },
    MuiOutlinedInput: { styleOverrides: { root: { backgroundColor: '#fff', fontSize: '.875rem' } } },
    MuiInputLabel: { styleOverrides: { root: { fontSize: '.875rem' } } },
    MuiChip: { styleOverrides: { root: { fontSize: '.7rem', fontWeight: 600, borderRadius: 5 } } },
    MuiTableCell: { styleOverrides: { head: { backgroundColor: '#fafbfc', color: '#737984', fontWeight: 600, fontSize: '.72rem' }, root: { borderColor: '#eef0f3', padding: '17px 20px', fontSize: '.82rem' } } },
    MuiDialog: { styleOverrides: { paper: { borderRadius: 14 } } },
    MuiAlert: { styleOverrides: { root: { borderRadius: 8, fontSize: '.82rem' } } },
  },
});
