package br.com.fiscal;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;

class InvoiceFormTest {
    static InvoiceForm.Tax tax(boolean enabled,String rate,String base){return new InvoiceForm.Tax(enabled,new BigDecimal(rate),new BigDecimal(base));}
    static InvoiceForm.Details details(boolean retained,String unconditional,String conditional) {
        String base=new BigDecimal("1000").subtract(new BigDecimal(unconditional)).toPlainString();
        var taxes=new InvoiceForm.Taxes(tax(retained,retained?"2":"5",base),tax(retained,retained?"0.65":"0",base),tax(retained,retained?"3":"0",base),tax(retained,retained?"1":"0",base),tax(retained,retained?"1.5":"0",base),tax(retained,retained?"11":"0",base),"01");
        return new InvoiceForm.Details(new NfseModels.Recipient("52998224725","Tomador do formulário","form@example.com",new NfseModels.Address("3550308","01001000","Rua de teste","10","Centro")),List.of(new InvoiceForm.Item(new BigDecimal("4"),new BigDecimal("250.00"))),"PIX",LocalDate.of(2026,12,10),new BigDecimal(unconditional),new BigDecimal(conditional),"Informação & complemento <teste>",taxes,new InvoiceForm.Approximation("SIMPLES",new BigDecimal("5"),null,null,null),"3550308","");
    }
    @Test void calculatesVideoExampleAndExplicitRetentionsWithExactDecimalRounding() {
        var simple=InvoiceForm.calculate(details(false,"0","0"));
        assertEquals(new BigDecimal("1000.00"),simple.gross());assertEquals(new BigDecimal("1000.00"),simple.estimatedNet());
        var retained=InvoiceForm.calculate(details(true,"100","10"));
        assertEquals(new BigDecimal("172.35"),retained.retained());assertEquals(new BigDecimal("717.65"),retained.estimatedNet());
        assertEquals(new BigDecimal("5.85"),retained.retentions().get("pis"));
        assertEquals(new BigDecimal("0.01"),InvoiceForm.money(new BigDecimal("0.005")));
        assertTrue(InvoiceForm.itemDescription("Serviço",details(false,"0","0")).contains("4 x R$ 250.00 = R$ 1000.00"));
        assertTrue(InvoiceForm.complement(details(false,"0","0")).contains("Forma de pagamento: PIX"));
    }
    @Test void rejectsDiscountsLargerThanServicesAndMissingApproximateTaxValues() {
        assertThrows(ResponseStatusException.class,()->InvoiceForm.calculate(details(false,"1001","0")));
        assertThrows(ResponseStatusException.class,()->InvoiceForm.totalTaxes(new InvoiceForm.Approximation("SIMPLES",null,null,null,null)));
        assertThrows(ResponseStatusException.class,()->InvoiceForm.totalTaxes(new InvoiceForm.Approximation("PERCENT",null,BigDecimal.ZERO,null,BigDecimal.ZERO)));
    }
}
